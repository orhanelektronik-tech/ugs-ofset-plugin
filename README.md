# UGS Ofset Kalibrasyonu Eklentisi

UGS (Universal Gcode Sender) 2.1.26 için lazer ↔ spindle ofset kalibrasyon eklentisi.
Lazer sökülüp spindle takıldığında oluşan, her seferinde farklı olan XY kaymasını
tablaya sabitlenmiş metal blok üzerindeki **delik problama** (merkez bulma) ile düzeltir.

Makine: MKS DLC32 V2.1 (Grbl_ESP32) · TB2EOS

## Mantık

- **L** = lazer noktası blok deliğinin merkezindeyken makine konumu
- **S** = spindle pimi deliğin merkezindeyken makine konumu (problanarak bulunur)
- **WCO** = lazer referansı alınırken aktif iş koordinat ofseti (kartın G54 sıfırı)
- **Yeni ofset = WCO + (S − L)** → `G10 L2 P1` (G54) veya `P2` (G55) ile yazılır

Delik problamada merkez = (a + b) / 2 olduğu için pim çapı sonuca girmez;
pim çapı sadece ölçülen delik çapının doğrulanmasında kullanılır.

## Dosyalar

```
ugs-platform-plugin-ofset/
├─ pom.xml
└─ src/main/
   ├─ nbm/manifest.mf
   ├─ resources/com/orhanelektronik/ugs/ofset/Bundle.properties
   └─ java/com/orhanelektronik/ugs/ofset/OfsetKalibrasyonTopComponent.java
```

## Kolay derleme (Windows)

Gereken: JDK 17+ ve Git. Maven gerekmez (UGS'nin `mvnw` aracı indirir).

`derle.bat` dosyasına çift tıkla. Betik UGS v2.1.26 kaynağını `ugs-src` klasörüne indirir,
eklentiyi içine kopyalar, derler ve `.nbm` dosyasını bu klasöre bırakır.

## Elle derleme

Gereken: JDK 17, Maven, Git

```
git clone https://github.com/winder/Universal-G-Code-Sender.git
cd Universal-G-Code-Sender
git tag -l "*2.1.26*"
git checkout <2.1.26 etiketi>
```

1. `ugs-platform-plugin-ofset` klasörünü `ugs-platform\` altına kopyala.
2. `ugs-platform\pom.xml` içindeki `<modules>` bölümüne ekle:
   ```xml
   <module>ugs-platform-plugin-ofset</module>
   ```
3. Derle:
   ```
   mvn install -DskipTests -pl ugs-platform/ugs-platform-plugin-ofset -am
   ```
4. Çıktı: `ugs-platform\ugs-platform-plugin-ofset\target\*.nbm`

## Kurulu UGS'ye ekleme

UGS → **Tools → Plugins → Downloaded → Add Plugins…** → .nbm seç → Install → yeniden başlat.
Panel: **Window → Plugins → Ofset Kalibrasyonu**

## Kullanım

1. **Home** ($H)
2. Lazer takılıyken kartın G54 sıfırını ayarla, deseni yak.
3. **İşaretçi AÇ** → lazer noktasını blok deliğinin merkezine getir → **Lazer referansını kaydet**
4. Lazeri sök, spindle tak, pense ölçülü pim tak. Blok probe pinine, krokodil pense/gövdeye.
5. Pimi deliğin içine, yüzeyin 2–3 mm altına indir → **Delik problamayı başlat**
6. **Ofseti uygula** → pimi çıkar, matkabı tak → **Z probla** → delme kodunu gönder.

> Lazer referansını kartın G54 sıfırını ayarladıktan **sonra** al.
