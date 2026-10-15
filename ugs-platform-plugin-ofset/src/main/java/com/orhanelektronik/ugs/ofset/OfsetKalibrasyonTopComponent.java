package com.orhanelektronik.ugs.ofset;

import com.willwinder.ugs.nbp.lib.lookup.CentralLookup;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.events.ProbeEvent;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.util.NbPreferences;
import org.openide.windows.TopComponent;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/**
 * Lazer / Spindle ofset kalibrasyonu - delik problama yöntemi.
 *
 * Mantık:
 *   L   = lazer noktası blok deliğinin merkezindeyken makine konumu
 *   S   = spindle pimi deliğin merkezindeyken makine konumu (probla bulunur)
 *   WCO = lazer referansı alınırken aktif olan iş koordinat ofseti (kartın G54 sıfırı)
 *   Yeni ofset = WCO + (S - L)  ->  G10 L2 P1/P2 ile yazılır
 */
@TopComponent.Description(
        preferredID = "OfsetKalibrasyonTopComponent",
        persistenceType = TopComponent.PERSISTENCE_NEVER)
@TopComponent.Registration(mode = "output", openAtStartup = false)
@ActionID(category = "Window", id = "com.orhanelektronik.ugs.ofset.OfsetKalibrasyonTopComponent")
@ActionReference(path = "Menu/Window/Plugins")
@TopComponent.OpenActionRegistration(
        displayName = "Ofset Kalibrasyonu",
        preferredID = "OfsetKalibrasyonTopComponent")
public final class OfsetKalibrasyonTopComponent extends TopComponent implements UGSEventListener {

    private interface Gorev {
        void calistir() throws Exception;
    }

    private final Preferences prefs = NbPreferences.forModule(OfsetKalibrasyonTopComponent.class);
    private final BackendAPI backend;
    private final BlockingQueue<Position> probeKuyrugu = new LinkedBlockingQueue<>();
    private volatile boolean calisiyor = false;
    private volatile boolean iptal = false;

    // ---- Ayar alanları (kalıcı) ----
    private final JTextField tfPimCap   = alan("pimCap", "3.175");
    private final JTextField tfDelikCap = alan("delikCap", "10.0");
    private final JTextField tfHizliF   = alan("hizliF", "100");
    private final JTextField tfYavasF   = alan("yavasF", "15");
    private final JTextField tfGeri     = alan("geriCekme", "0.5");
    private final JTextField tfLazerS   = alan("lazerS", "5");
    private final JTextField tfZArama   = alan("zArama", "10");
    private final JTextField tfZPlaka   = alan("zPlaka", "0.0");
    private final JComboBox<String> cbHedef = new JComboBox<>(new String[]{"G54 (P1)", "G55 (P2)"});

    // ---- Durum etiketleri ----
    private final JLabel lblLazer   = new JLabel();
    private final JLabel lblSpindle = new JLabel();
    private final JLabel lblFark    = new JLabel();
    private final JTextArea taLog   = new JTextArea(10, 50);
    private final List<JButton> gorevButonlari = new ArrayList<>();

    public OfsetKalibrasyonTopComponent() {
        setName("Ofset Kalibrasyonu");
        setToolTipText("Lazer / Spindle ofset kalibrasyonu (delik problama)");
        backend = CentralLookup.getDefault().lookup(BackendAPI.class);

        cbHedef.setSelectedIndex(prefs.getInt("hedef", 0));
        cbHedef.addActionListener(e -> prefs.putInt("hedef", cbHedef.getSelectedIndex()));

        arayuzuOlustur();
        etiketleriGuncelle();
    }

    // =====================================================================
    //  ARAYÜZ
    // =====================================================================
    private void arayuzuOlustur() {
        setLayout(new BorderLayout());
        JPanel ana = new JPanel();
        ana.setLayout(new BoxLayout(ana, BoxLayout.Y_AXIS));

        // Ayarlar
        JPanel ayar = panel("Ayarlar");
        ayar.setLayout(new GridLayout(0, 4, 6, 4));
        satir(ayar, "Pim çapı (mm)", tfPimCap);
        satir(ayar, "Delik çapı (mm)", tfDelikCap);
        satir(ayar, "Hızlı prob F (mm/dk)", tfHizliF);
        satir(ayar, "Yavaş prob F (mm/dk)", tfYavasF);
        satir(ayar, "Geri çekme (mm)", tfGeri);
        satir(ayar, "Lazer işaretçi gücü S", tfLazerS);
        satir(ayar, "Z arama mesafesi (mm)", tfZArama);
        satir(ayar, "Z plaka kalınlığı (mm)", tfZPlaka);
        ayar.add(new JLabel("Yazılacak koordinat"));
        ayar.add(cbHedef);
        ayar.add(new JLabel());
        ayar.add(new JLabel());
        ana.add(ayar);

        // 1 - Hazırlık
        JPanel p1 = panel("1 - Hazırlık");
        p1.add(gorevButonu("Home ($H)", "Home", () -> gonder("$H")));
        JButton durdur = new JButton("DURDUR");
        durdur.setForeground(Color.RED);
        durdur.addActionListener(e -> durdur());
        p1.add(durdur);
        ana.add(p1);

        // 2 - Lazer referansı
        JPanel p2 = panel("2 - Lazer referansı (lazer takılı, kart G54 sıfırı ayarlanmış)");
        p2.add(gorevButonu("İşaretçi AÇ", "Lazer işaretçi aç", this::lazerIsaretciAc));
        p2.add(gorevButonu("İşaretçi KAPAT", "Lazer işaretçi kapat", this::lazerIsaretciKapat));
        p2.add(gorevButonu("Lazer referansını kaydet", "Lazer referansı", this::lazerReferansKaydet));
        p2.add(lblLazer);
        ana.add(p2);

        // 3 - Spindle referansı
        JPanel p3 = panel("3 - Spindle referansı (pim takılı, pim deliğin içinde, yüzeyin 2-3 mm altında)");
        p3.add(gorevButonu("Delik problamayı başlat", "Delik problama", this::delikProbla));
        p3.add(lblSpindle);
        ana.add(p3);

        // 4 - Uygula
        JPanel p4 = panel("4 - Ofseti uygula");
        p4.add(gorevButonu("Ofseti uygula", "Ofset uygula", this::ofsetUygula));
        p4.add(gorevButonu("Z probla (PCB yüzeyi)", "Z problama", this::zProbla));
        p4.add(lblFark);
        ana.add(p4);

        // Log
        taLog.setEditable(false);
        taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logKaydir = new JScrollPane(taLog);
        logKaydir.setBorder(new TitledBorder("Kayıt"));

        add(new JScrollPane(ana), BorderLayout.CENTER);
        add(logKaydir, BorderLayout.SOUTH);
    }

    private JPanel panel(String baslik) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setBorder(new TitledBorder(baslik));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private void satir(JPanel p, String etiket, JComponent bilesen) {
        p.add(new JLabel(etiket));
        p.add(bilesen);
    }

    private JTextField alan(String anahtar, String varsayilan) {
        JTextField tf = new JTextField(prefs.get(anahtar, varsayilan), 7);
        tf.putClientProperty("anahtar", anahtar);
        return tf;
    }

    private JButton gorevButonu(String yazi, String ad, Gorev g) {
        JButton b = new JButton(yazi);
        b.addActionListener(e -> gorevCalistir(ad, g));
        gorevButonlari.add(b);
        return b;
    }

    private void butonlariAyarla(boolean aktif) {
        for (JButton b : gorevButonlari) {
            b.setEnabled(aktif);
        }
    }

    // =====================================================================
    //  GÖREV ÇALIŞTIRMA
    // =====================================================================
    private void gorevCalistir(String ad, Gorev g) {
        if (calisiyor) {
            return;
        }
        if (backend == null || !backend.isConnected()) {
            log("HATA: Makine bağlı değil.");
            return;
        }
        calisiyor = true;
        iptal = false;
        butonlariAyarla(false);

        new Thread(() -> {
            try {
                log("== " + ad + " ==");
                g.calistir();
                log("Tamam.");
            } catch (Exception ex) {
                log("HATA: " + ex.getMessage());
                try {
                    backend.sendGcodeCommand("G90");
                } catch (Exception ignored) {
                }
            } finally {
                calisiyor = false;
                SwingUtilities.invokeLater(() -> butonlariAyarla(true));
            }
        }, "ofset-kalibrasyon").start();
    }

    private void durdur() {
        iptal = true;
        try {
            backend.issueSoftReset();
            log("DURDURULDU (soft reset). Gerekirse $X ile kilidi aç.");
        } catch (Exception ex) {
            log("Durdurma hatası: " + ex.getMessage());
        }
    }

    // =====================================================================
    //  ADIM 2 - LAZER
    // =====================================================================
    private void lazerIsaretciAc() throws Exception {
        gonder("$32=0");
        gonder("M3 S" + f(sayi(tfLazerS)));
        log("DİKKAT: Lazer AÇIK - koruyucu gözlük tak!");
        log("Jog ile lazer noktasını blok deliğinin tam merkezine getir.");
    }

    private void lazerIsaretciKapat() throws Exception {
        gonder("M5");
        gonder("$32=1");
    }

    private void lazerReferansKaydet() throws Exception {
        Position m = makinePoz();
        Position w = isPoz();
        double wcoX = m.getX() - w.getX();
        double wcoY = m.getY() - w.getY();

        prefs.putDouble("lx", m.getX());
        prefs.putDouble("ly", m.getY());
        prefs.putDouble("wcoX", wcoX);
        prefs.putDouble("wcoY", wcoY);

        log("Lazer referansı L = X" + f(m.getX()) + " Y" + f(m.getY()));
        log("Kartın iş ofseti (WCO) = X" + f(wcoX) + " Y" + f(wcoY));
        lazerIsaretciKapat();
        etiketleriGuncelle();
    }

    // =====================================================================
    //  ADIM 3 - DELİK PROBLAMA
    // =====================================================================
    private void delikProbla() throws Exception {
        double pim = sayi(tfPimCap);
        double delik = sayi(tfDelikCap);
        if (pim >= delik) {
            throw new Exception("Pim çapı delik çapından küçük olmalı.");
        }
        double maxMesafe = delik / 2.0 + 2.0;

        gonder("G21");
        gonder("G90");
        Position bas = makinePoz();
        double x0 = bas.getX();
        double y0 = bas.getY();

        // 1. tur X
        double a = eksenProbla('X', +1, maxMesafe);
        gonder("G53 G0 X" + f(x0));
        double b = eksenProbla('X', -1, maxMesafe);
        double cx = (a + b) / 2.0;
        gonder("G53 G0 X" + f(cx));

        // Y
        double c = eksenProbla('Y', +1, maxMesafe);
        gonder("G53 G0 Y" + f(y0));
        double d = eksenProbla('Y', -1, maxMesafe);
        double cy = (c + d) / 2.0;
        gonder("G53 G0 Y" + f(cy));

        // 2. tur X (artık Y merkezde, çap ölçümü doğru olur)
        a = eksenProbla('X', +1, maxMesafe);
        gonder("G53 G0 X" + f(cx));
        b = eksenProbla('X', -1, maxMesafe);
        cx = (a + b) / 2.0;
        gonder("G53 G0 X" + f(cx));

        double olcX = (a - b) + pim;
        double olcY = (c - d) + pim;
        log("Ölçülen delik çapı  X: " + f(olcX) + "  Y: " + f(olcY));
        if (Math.abs(olcX - delik) > 0.3 || Math.abs(olcY - delik) > 0.3) {
            log("UYARI: Ölçülen çap girilen delik çapından farklı. Talaş/temas veya delik çapı ayarını kontrol et.");
        }
        if (Math.abs(olcX - olcY) > 0.05) {
            log("UYARI: X ve Y çapları farklı (>0.05 mm). Pim salgısı veya talaş olabilir.");
        }

        prefs.putDouble("sx", cx);
        prefs.putDouble("sy", cy);
        log("Spindle referansı S = X" + f(cx) + " Y" + f(cy));
        etiketleriGuncelle();
    }

    /** Hızlı + yavaş iki aşamalı problama. Temas anındaki makine koordinatını döndürür. */
    private double eksenProbla(char eksen, int yon, double maxMesafe) throws Exception {
        double hizli = sayi(tfHizliF);
        double yavas = sayi(tfYavasF);
        double geri = sayi(tfGeri);

        probeKuyrugu.clear();
        gonder("G91 G38.2 " + eksen + f(yon * maxMesafe) + " F" + f(hizli));
        probeBekle(maxMesafe, hizli);

        gonder("G91 G0 " + eksen + f(-yon * geri));

        probeKuyrugu.clear();
        gonder("G91 G38.2 " + eksen + f(yon * geri * 2.0) + " F" + f(yavas));
        Position p = probeBekle(geri * 2.0, yavas);
        gonder("G90");

        double v;
        switch (eksen) {
            case 'X': v = p.getX(); break;
            case 'Y': v = p.getY(); break;
            default:  v = p.getZ(); break;
        }
        log(String.format("  %c%s temas: %s", eksen, yon > 0 ? "+" : "-", f(v)));
        return v;
    }

    private Position probeBekle(double mesafe, double feed) throws Exception {
        long sure = (long) (Math.abs(mesafe) / feed * 60000.0) + 5000;
        long bitis = System.currentTimeMillis() + sure;
        while (true) {
            if (iptal) {
                throw new Exception("Kullanıcı durdurdu.");
            }
            Position p = probeKuyrugu.poll(200, TimeUnit.MILLISECONDS);
            if (p != null) {
                return p.getPositionIn(UnitUtils.Units.MM);
            }
            if (System.currentTimeMillis() > bitis) {
                throw new Exception("Prob temas etmedi (zaman aşımı). Alarm varsa $X ile temizle, kabloyu kontrol et.");
            }
        }
    }

    // =====================================================================
    //  ADIM 4 - OFSET UYGULA / Z
    // =====================================================================
    private void ofsetUygula() throws Exception {
        double lx = prefs.getDouble("lx", Double.NaN);
        double ly = prefs.getDouble("ly", Double.NaN);
        double sx = prefs.getDouble("sx", Double.NaN);
        double sy = prefs.getDouble("sy", Double.NaN);
        double wcoX = prefs.getDouble("wcoX", Double.NaN);
        double wcoY = prefs.getDouble("wcoY", Double.NaN);
        if (Double.isNaN(lx) || Double.isNaN(sx) || Double.isNaN(wcoX)) {
            throw new Exception("Önce lazer referansını ve delik problamayı yap.");
        }

        double dx = sx - lx;
        double dy = sy - ly;
        double yeniX = wcoX + dx;
        double yeniY = wcoY + dy;
        int p = cbHedef.getSelectedIndex() + 1;

        gonder(String.format(Locale.US, "G10 L2 P%d X%s Y%s", p, f(yeniX), f(yeniY)));
        gonder(p == 1 ? "G54" : "G55");

        log("Kayma Δ = X" + f(dx) + " Y" + f(dy));
        log((p == 1 ? "G54" : "G55") + " yeni ofset = X" + f(yeniX) + " Y" + f(yeniY));
        if (p == 2) {
            log("Not: Delme G-code'unun başında G55 olmalı.");
        }
        etiketleriGuncelle();
    }

    private void zProbla() throws Exception {
        double ara = sayi(tfZArama);
        double plaka = sayi(tfZPlaka);
        int p = cbHedef.getSelectedIndex() + 1;

        gonder("G21");
        double z = eksenProbla('Z', -1, ara);
        gonder("G10 L20 P" + p + " Z" + f(plaka));
        gonder("G91 G0 Z2");
        gonder("G90");
        log("Z sıfırlandı (temas Z" + f(z) + ", plaka " + f(plaka) + " mm).");
    }

    // =====================================================================
    //  YARDIMCILAR
    // =====================================================================
    private void gonder(String komut) throws Exception {
        if (iptal) {
            throw new Exception("Kullanıcı durdurdu.");
        }
        log("> " + komut);
        backend.sendGcodeCommand(komut);
    }

    private Position makinePoz() {
        return backend.getMachinePosition().getPositionIn(UnitUtils.Units.MM);
    }

    private Position isPoz() {
        return backend.getWorkPosition().getPositionIn(UnitUtils.Units.MM);
    }

    private double sayi(JTextField tf) throws Exception {
        String s = tf.getText().trim().replace(',', '.');
        try {
            double v = Double.parseDouble(s);
            Object anahtar = tf.getClientProperty("anahtar");
            if (anahtar != null) {
                prefs.put(anahtar.toString(), s);
            }
            return v;
        } catch (NumberFormatException ex) {
            throw new Exception("Geçersiz sayı: '" + tf.getText() + "'");
        }
    }

    private static String f(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private void etiketleriGuncelle() {
        double lx = prefs.getDouble("lx", Double.NaN), ly = prefs.getDouble("ly", Double.NaN);
        double sx = prefs.getDouble("sx", Double.NaN), sy = prefs.getDouble("sy", Double.NaN);
        SwingUtilities.invokeLater(() -> {
            lblLazer.setText(Double.isNaN(lx) ? "L: -" : "L: X" + f(lx) + " Y" + f(ly));
            lblSpindle.setText(Double.isNaN(sx) ? "S: -" : "S: X" + f(sx) + " Y" + f(sy));
            lblFark.setText(Double.isNaN(lx) || Double.isNaN(sx) ? "Δ: -"
                    : "Δ: X" + f(sx - lx) + " Y" + f(sy - ly));
        });
    }

    private void log(String mesaj) {
        SwingUtilities.invokeLater(() -> {
            taLog.append(mesaj + "\n");
            taLog.setCaretPosition(taLog.getDocument().getLength());
        });
    }

    // =====================================================================
    //  UGS OLAYLARI
    // =====================================================================
    @Override
    public void UGSEvent(UGSEvent evt) {
        if (evt instanceof ProbeEvent) {
            probeKuyrugu.offer(((ProbeEvent) evt).getProbePosition());
        }
    }

    @Override
    public void componentOpened() {
        if (backend != null) {
            backend.addUGSEventListener(this);
        }
    }

    @Override
    public void componentClosed() {
        if (backend != null) {
            backend.removeUGSEventListener(this);
        }
    }
}
