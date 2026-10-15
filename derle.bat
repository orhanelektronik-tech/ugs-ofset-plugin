@echo off
setlocal
rem ==========================================================
rem  UGS Ofset Kalibrasyonu eklentisi - otomatik derleme
rem  Gereken: JDK 17+  (Git ve Maven gerekmez)
rem  Cikti: bu klasore ugs-platform-plugin-ofset-*.nbm
rem ==========================================================

set "PROJE=%~dp0"
set "SRC=%PROJE%ugs-src"
set "UGS_SURUM=2.1.26"
set "ZIP=%PROJE%ugs-kaynak.zip"
set "ZIP_URL=https://github.com/winder/Universal-G-Code-Sender/archive/refs/tags/v%UGS_SURUM%.zip"

echo.
echo === 1/5 Arac kontrolu ===
where java >nul 2>&1
if errorlevel 1 (
    echo [HATA] Java bulunamadi. JDK 17 kur: https://adoptium.net
    echo        Kurulumda "Set JAVA_HOME" secenegini isaretle.
    goto :hata
)
java -version

echo.
echo === 2/5 UGS v%UGS_SURUM% kaynak kodu ===
if exist "%SRC%\pom.xml" (
    echo Kaynak kod zaten var, indirme atlandi.
    goto :kopyala
)

where git >nul 2>&1
if not errorlevel 1 (
    echo Git ile indiriliyor...
    git clone --depth 1 --branch v%UGS_SURUM% https://github.com/winder/Universal-G-Code-Sender.git "%SRC%"
    if errorlevel 1 goto :hata
    goto :kopyala
)

echo Git yok, zip olarak indiriliyor (yaklasik 60 MB)...
powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%ZIP_URL%' -OutFile '%ZIP%'"
if errorlevel 1 goto :hata
if not exist "%ZIP%" goto :hata

echo Zip aciliyor...
if exist "%PROJE%ugs-gecici" rmdir /S /Q "%PROJE%ugs-gecici"
powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Expand-Archive -Path '%ZIP%' -DestinationPath '%PROJE%ugs-gecici' -Force"
if errorlevel 1 goto :hata
move "%PROJE%ugs-gecici\Universal-G-Code-Sender-%UGS_SURUM%" "%SRC%" >nul
if errorlevel 1 goto :hata
rmdir /S /Q "%PROJE%ugs-gecici"
del /Q "%ZIP%"

:kopyala
echo.
echo === 3/5 Eklenti dosyalari kopyalaniyor ===
xcopy /E /I /Y "%PROJE%ugs-platform-plugin-ofset" "%SRC%\ugs-platform\ugs-platform-plugin-ofset" >nul
if errorlevel 1 goto :hata

findstr /C:"<module>ugs-platform-plugin-ofset</module>" "%SRC%\ugs-platform\pom.xml" >nul
if errorlevel 1 (
    echo Modul ugs-platform\pom.xml dosyasina ekleniyor...
    powershell -NoProfile -Command "$p='%SRC%\ugs-platform\pom.xml'; $t=[IO.File]::ReadAllText($p); $a='<module>ugs-platform-plugin-interceptor</module>'; $t=$t.Replace($a, $a + [char]13 + [char]10 + '    <module>ugs-platform-plugin-ofset</module>'); [IO.File]::WriteAllText($p,$t)"
    if errorlevel 1 goto :hata
)

echo.
echo === 4/5 Derleniyor (ilk seferde 10-20 dk surebilir) ===
pushd "%SRC%"
call mvnw.cmd -B -DskipTests install -pl ugs-platform/ugs-platform-plugin-ofset -am
if errorlevel 1 (
    popd
    goto :hata
)
popd

echo.
echo === 5/5 .nbm dosyasi kopyalaniyor ===
copy /Y "%SRC%\ugs-platform\ugs-platform-plugin-ofset\target\*.nbm" "%PROJE%"
if errorlevel 1 goto :hata

echo.
echo ==========================================================
echo  TAMAM. .nbm dosyasi bu klasorde:
echo  %PROJE%
echo  UGS: Tools - Plugins - Downloaded - Add Plugins ile kur.
echo ==========================================================
pause
exit /b 0

:hata
echo.
echo ==========================================================
echo  [HATA] Derleme durdu. Yukaridaki son 30-40 satiri
echo  Claude'a yapistir.
echo ==========================================================
pause
exit /b 1
