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
 * Spindle ofset kalibrasyonu - delik problama yöntemi (lazer bölümü yok).
 *
 * Mantık:
 *   L    = Referans noktası (blok deliğinin merkezi) - ayarlarda SABİT girilir
 *          (lazer işinde kullanılan koordinata karşılık gelen makine konumu)
 *   S    = Spindle pimi deliğin merkezindeyken makine konumu (probla bulunur)
 *   TABAN = Probla anında aktif olan iş koordinat ofseti (kartın G54 sıfırı)
 *   Yeni ofset = TABAN + (S - L)  ->  G10 L2 P1/P2 ile yazılır
 *
 * Çift uygulama koruması: Ofset uygulandıktan sonra tekrar problanırsa TABAN
 * olarak uygulanmış değer değil, ilk taban kullanılır.
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

    private static final double ESIT_TOL = 0.0005;

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
    private final JTextField tfZArama   = alan("zArama", "10");
    private final JTextField tfZPlaka   = alan("zPlaka", "0.0");
    private final JComboBox<String> cbHedef = new JComboBox<>(new String[]{"G54 (P1)", "G55 (P2)"});
    private final JCheckBox chSpindleModu = new JCheckBox("Uygularken spindle moduna geç ($32=0)");

    // ---- Referans (sabit) ----
    private final JTextField tfRefX = alan("refX", "");
    private final JTextField tfRefY = alan("refY", "");

    // ---- Durum etiketleri ----
    private final JLabel lblSpindle = new JLabel();
    private final JLabel lblFark    = new JLabel();
    private final JTextArea taLog   = new JTextArea(10, 50);
    private final List<JButton> gorevButonlari = new ArrayList<>();

    public OfsetKalibrasyonTopComponent() {
        setName("Ofset Kalibrasyonu");
        setToolTipText("Spindle ofset kalibrasyonu (delik problama)");
        backend = CentralLookup.getDefault().lookup(BackendAPI.class);

        cbHedef.setSelectedIndex(prefs.getInt("hedef", 0));
        cbHedef.addActionListener(e -> prefs.putInt("hedef", cbHedef.getSelectedIndex()));
        chSpindleModu.setSelected(prefs.getBoolean("spindleModu", true));
        chSpindleModu.addActionListener(e -> prefs.putBoolean("spindleModu", chSpindleModu.isSelected()));

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

        // Ayarlar - kutular doğal boyutunda, satır başına 2 ayar
        JPanel ayar = panel("Ayarlar");
        ayar.setLayout(new GridBagLayout());
        int sira = 0;
        sira = ayarEkle(ayar, sira, "Pim çapı (mm)", tfPimCap);
        sira = ayarEkle(ayar, sira, "Delik çapı (mm)", tfDelikCap);
        sira = ayarEkle(ayar, sira, "Hızlı prob F (mm/dk)", tfHizliF);
        sira = ayarEkle(ayar, sira, "Yavaş prob F (mm/dk)", tfYavasF);
        sira = ayarEkle(ayar, sira, "Geri çekme (mm)", tfGeri);
        sira = ayarEkle(ayar, sira, "Z arama mesafesi (mm)", tfZArama);
        sira = ayarEkle(ayar, sira, "Z plaka kalınlığı (mm)", tfZPlaka);
        sira = ayarEkle(ayar, sira, "Yazılacak koordinat", cbHedef);

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = (sira + 1) / 2;
        gc.gridwidth = 4;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(4, 4, 2, 4);
        ayar.add(chSpindleModu, gc);

        // Sağa boşluk: kutular sola yaslı kalsın
        GridBagConstraints bos = new GridBagConstraints();
        bos.gridx = 4;
        bos.gridy = 0;
        bos.weightx = 1.0;
        ayar.add(Box.createHorizontalGlue(), bos);
        ana.add(ayar);

        // 1 - Hazırlık
        JPanel p1 = panel("1 - Hazırlık");
        p1.add(gorevButonu("Home ($H)", "Home", () -> gonder("$H")));
        JButton durdur = new JButton("DURDUR");
        durdur.setForeground(Color.RED);
        durdur.addActionListener(e -> durdur());
        p1.add(durdur);
        ana.add(p1);

        // 2 - Referans (sabit)
        JPanel p2 = panel("2 - Referans noktası (blok deliği merkezi, makine koordinatı - bir kez girilir)");
        p2.add(new JLabel("X"));
        p2.add(tfRefX);
        p2.add(new JLabel("Y"));
        p2.add(tfRefY);
        p2.add(gorevButonu("Mevcut konumu yaz", "Mevcut konumu referansa yaz", this::mevcutKonumuReferansaYaz));
        ana.add(p2);

        // 3 - Spindle problama
        JPanel p3 = panel("3 - Spindle (pim takılı, pim deliğin içinde, yüzeyin 2-3 mm altında)");
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

        // Bölümler üste yaslı kalsın, boş alan aşağıda olsun
        JPanel kap = new JPanel(new BorderLayout());
        kap.add(ana, BorderLayout.NORTH);
        add(new JScrollPane(kap), BorderLayout.CENTER);
        add(logKaydir, BorderLayout.SOUTH);
    }

    private JPanel panel(String baslik) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setBorder(new TitledBorder(baslik));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Ayar panelinde etiket + kutu çifti ekler (satır başına 2 çift). Sonraki sırayı döndürür. */
    private int ayarEkle(JPanel p, int sira, String etiket, JComponent bilesen) {
        int satirNo = sira / 2;
        int sutun = (sira % 2) * 2;

        GridBagConstraints ge = new GridBagConstraints();
        ge.gridx = sutun;
        ge.gridy = satirNo;
        ge.anchor = GridBagConstraints.WEST;
        ge.insets = new Insets(2, sutun == 0 ? 4 : 24, 2, 6);
        p.add(new JLabel(etiket), ge);

        GridBagConstraints gb = new GridBagConstraints();
        gb.gridx = sutun + 1;
        gb.gridy = satirNo;
        gb.anchor = GridBagConstraints.WEST;
        gb.fill = GridBagConstraints.NONE;
        gb.insets = new Insets(2, 0, 2, 4);
        p.add(bilesen, gb);

        return sira + 1;
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
    //  ADIM 2 - REFERANS
    // =====================================================================
    private void mevcutKonumuReferansaYaz() {
        Position m = makinePoz();
        String x = f(m.getX());
        String y = f(m.getY());
        prefs.put("refX", x);
        prefs.put("refY", y);
        SwingUtilities.invokeLater(() -> {
            tfRefX.setText(x);
            tfRefY.setText(y);
        });
        log("Referans = X" + x + " Y" + y + " (makine koordinatı)");
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

        // Taban iş ofsetini al (çift uygulama korumalı)
        tabanOfsetiKaydet();

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
        log("Spindle merkezi S = X" + f(cx) + " Y" + f(cy));
        etiketleriGuncelle();
    }

    /**
     * Aktif iş ofsetini taban olarak saklar. Eğer aktif ofset, eklentinin en son
     * uyguladığı değerle aynıysa (yani ofset zaten uygulanmışsa) eski taban korunur.
     */
    private void tabanOfsetiKaydet() {
        Position m = makinePoz();
        Position w = isPoz();
        double wcoX = m.getX() - w.getX();
        double wcoY = m.getY() - w.getY();

        double uygX = prefs.getDouble("uygX", Double.NaN);
        double uygY = prefs.getDouble("uygY", Double.NaN);
        boolean zatenUygulanmis = !Double.isNaN(uygX)
                && Math.abs(wcoX - uygX) < ESIT_TOL
                && Math.abs(wcoY - uygY) < ESIT_TOL
                && !Double.isNaN(prefs.getDouble("tabanX", Double.NaN));

        if (zatenUygulanmis) {
            log("Ofset daha önce uygulanmış; önceki taban korunuyor (X"
                    + f(prefs.getDouble("tabanX", 0)) + " Y" + f(prefs.getDouble("tabanY", 0)) + ").");
        } else {
            prefs.putDouble("tabanX", wcoX);
            prefs.putDouble("tabanY", wcoY);
            log("Taban iş ofseti = X" + f(wcoX) + " Y" + f(wcoY));
        }
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
        if (tfRefX.getText().trim().isEmpty() || tfRefY.getText().trim().isEmpty()) {
            throw new Exception("Önce 2. bölümde referans noktasını (X/Y) gir.");
        }
        double lx = sayi(tfRefX);
        double ly = sayi(tfRefY);
        double sx = prefs.getDouble("sx", Double.NaN);
        double sy = prefs.getDouble("sy", Double.NaN);
        double tabanX = prefs.getDouble("tabanX", Double.NaN);
        double tabanY = prefs.getDouble("tabanY", Double.NaN);
        if (Double.isNaN(sx) || Double.isNaN(tabanX)) {
            throw new Exception("Önce delik problamayı yap.");
        }

        double dx = sx - lx;
        double dy = sy - ly;
        double yeniX = tabanX + dx;
        double yeniY = tabanY + dy;
        int p = cbHedef.getSelectedIndex() + 1;

        if (chSpindleModu.isSelected()) {
            gonder("$32=0");
        }
        gonder(String.format(Locale.US, "G10 L2 P%d X%s Y%s", p, f(yeniX), f(yeniY)));
        gonder(p == 1 ? "G54" : "G55");

        prefs.putDouble("uygX", yeniX);
        prefs.putDouble("uygY", yeniY);

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

    private static double guvenliSayi(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    private void etiketleriGuncelle() {
        double lx = guvenliSayi(prefs.get("refX", ""));
        double ly = guvenliSayi(prefs.get("refY", ""));
        double sx = prefs.getDouble("sx", Double.NaN), sy = prefs.getDouble("sy", Double.NaN);
        SwingUtilities.invokeLater(() -> {
            lblSpindle.setText(Double.isNaN(sx) ? "S: -" : "S: X" + f(sx) + " Y" + f(sy));
            lblFark.setText(Double.isNaN(lx) || Double.isNaN(ly) || Double.isNaN(sx) ? "Δ: -"
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
 * Spindle ofset kalibrasyonu - delik problama yöntemi (lazer bölümü yok).
 *
 * Mantık:
 *   L    = Referans noktası (blok deliğinin merkezi) - ayarlarda SABİT girilir
 *          (lazer işinde kullanılan koordinata karşılık gelen makine konumu)
 *   S    = Spindle pimi deliğin merkezindeyken makine konumu (probla bulunur)
 *   TABAN = Probla anında aktif olan iş koordinat ofseti (kartın G54 sıfırı)
 *   Yeni ofset = TABAN + (S - L)  ->  G10 L2 P1/P2 ile yazılır
 *
 * Çift uygulama koruması: Ofset uygulandıktan sonra tekrar problanırsa TABAN
 * olarak uygulanmış değer değil, ilk taban kullanılır.
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

    private static final double ESIT_TOL = 0.0005;

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
    private final JTextField tfZArama   = alan("zArama", "10");
    private final JTextField tfZPlaka   = alan("zPlaka", "0.0");
    private final JComboBox<String> cbHedef = new JComboBox<>(new String[]{"G54 (P1)", "G55 (P2)"});
    private final JCheckBox chSpindleModu = new JCheckBox("Uygularken spindle moduna geç ($32=0)");

    // ---- Referans (sabit) ----
    private final JTextField tfRefX = alan("refX", "");
    private final JTextField tfRefY = alan("refY", "");

    // ---- Durum etiketleri ----
    private final JLabel lblSpindle = new JLabel();
    private final JLabel lblFark    = new JLabel();
    private final JTextArea taLog   = new JTextArea(10, 50);
    private final List<JButton> gorevButonlari = new ArrayList<>();

    public OfsetKalibrasyonTopComponent() {
        setName("Ofset Kalibrasyonu");
        setToolTipText("Spindle ofset kalibrasyonu (delik problama)");
        backend = CentralLookup.getDefault().lookup(BackendAPI.class);

        cbHedef.setSelectedIndex(prefs.getInt("hedef", 0));
        cbHedef.addActionListener(e -> prefs.putInt("hedef", cbHedef.getSelectedIndex()));
        chSpindleModu.setSelected(prefs.getBoolean("spindleModu", true));
        chSpindleModu.addActionListener(e -> prefs.putBoolean("spindleModu", chSpindleModu.isSelected()));

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
        satir(ayar, "Z arama mesafesi (mm)", tfZArama);
        satir(ayar, "Z plaka kalınlığı (mm)", tfZPlaka);
        satir(ayar, "Yazılacak koordinat", cbHedef);
        ayar.add(chSpindleModu);
        ana.add(ayar);

        // 1 - Hazırlık
        JPanel p1 = panel("1 - Hazırlık");
        p1.add(gorevButonu("Home ($H)", "Home", () -> gonder("$H")));
        JButton durdur = new JButton("DURDUR");
        durdur.setForeground(Color.RED);
        durdur.addActionListener(e -> durdur());
        p1.add(durdur);
        ana.add(p1);

        // 2 - Referans (sabit)
        JPanel p2 = panel("2 - Referans noktası (blok deliği merkezi, makine koordinatı - bir kez girilir)");
        p2.add(new JLabel("X"));
        p2.add(tfRefX);
        p2.add(new JLabel("Y"));
        p2.add(tfRefY);
        p2.add(gorevButonu("Mevcut konumu yaz", "Mevcut konumu referansa yaz", this::mevcutKonumuReferansaYaz));
        ana.add(p2);

        // 3 - Spindle problama
        JPanel p3 = panel("3 - Spindle (pim takılı, pim deliğin içinde, yüzeyin 2-3 mm altında)");
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
    //  ADIM 2 - REFERANS
    // =====================================================================
    private void mevcutKonumuReferansaYaz() {
        Position m = makinePoz();
        String x = f(m.getX());
        String y = f(m.getY());
        prefs.put("refX", x);
        prefs.put("refY", y);
        SwingUtilities.invokeLater(() -> {
            tfRefX.setText(x);
            tfRefY.setText(y);
        });
        log("Referans = X" + x + " Y" + y + " (makine koordinatı)");
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

        // Taban iş ofsetini al (çift uygulama korumalı)
        tabanOfsetiKaydet();

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
        log("Spindle merkezi S = X" + f(cx) + " Y" + f(cy));
        etiketleriGuncelle();
    }

    /**
     * Aktif iş ofsetini taban olarak saklar. Eğer aktif ofset, eklentinin en son
     * uyguladığı değerle aynıysa (yani ofset zaten uygulanmışsa) eski taban korunur.
     */
    private void tabanOfsetiKaydet() {
        Position m = makinePoz();
        Position w = isPoz();
        double wcoX = m.getX() - w.getX();
        double wcoY = m.getY() - w.getY();

        double uygX = prefs.getDouble("uygX", Double.NaN);
        double uygY = prefs.getDouble("uygY", Double.NaN);
        boolean zatenUygulanmis = !Double.isNaN(uygX)
                && Math.abs(wcoX - uygX) < ESIT_TOL
                && Math.abs(wcoY - uygY) < ESIT_TOL
                && !Double.isNaN(prefs.getDouble("tabanX", Double.NaN));

        if (zatenUygulanmis) {
            log("Ofset daha önce uygulanmış; önceki taban korunuyor (X"
                    + f(prefs.getDouble("tabanX", 0)) + " Y" + f(prefs.getDouble("tabanY", 0)) + ").");
        } else {
            prefs.putDouble("tabanX", wcoX);
            prefs.putDouble("tabanY", wcoY);
            log("Taban iş ofseti = X" + f(wcoX) + " Y" + f(wcoY));
        }
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
        if (tfRefX.getText().trim().isEmpty() || tfRefY.getText().trim().isEmpty()) {
            throw new Exception("Önce 2. bölümde referans noktasını (X/Y) gir.");
        }
        double lx = sayi(tfRefX);
        double ly = sayi(tfRefY);
        double sx = prefs.getDouble("sx", Double.NaN);
        double sy = prefs.getDouble("sy", Double.NaN);
        double tabanX = prefs.getDouble("tabanX", Double.NaN);
        double tabanY = prefs.getDouble("tabanY", Double.NaN);
        if (Double.isNaN(sx) || Double.isNaN(tabanX)) {
            throw new Exception("Önce delik problamayı yap.");
        }

        double dx = sx - lx;
        double dy = sy - ly;
        double yeniX = tabanX + dx;
        double yeniY = tabanY + dy;
        int p = cbHedef.getSelectedIndex() + 1;

        if (chSpindleModu.isSelected()) {
            gonder("$32=0");
        }
        gonder(String.format(Locale.US, "G10 L2 P%d X%s Y%s", p, f(yeniX), f(yeniY)));
        gonder(p == 1 ? "G54" : "G55");

        prefs.putDouble("uygX", yeniX);
        prefs.putDouble("uygY", yeniY);

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

    private static double guvenliSayi(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    private void etiketleriGuncelle() {
        double lx = guvenliSayi(prefs.get("refX", ""));
        double ly = guvenliSayi(prefs.get("refY", ""));
        double sx = prefs.getDouble("sx", Double.NaN), sy = prefs.getDouble("sy", Double.NaN);
        SwingUtilities.invokeLater(() -> {
            lblSpindle.setText(Double.isNaN(sx) ? "S: -" : "S: X" + f(sx) + " Y" + f(sy));
            lblFark.setText(Double.isNaN(lx) || Double.isNaN(ly) || Double.isNaN(sx) ? "Δ: -"
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
