@echo off
setlocal
rem ==========================================================
rem  UGS Ofset Kalibrasyonu eklentisi - otomatik derleme
rem  Gerekenler: JDK 17+ ve Git (Maven gerekmez, mvnw indirir)
rem  Cikti: bu klasore ugs-platform-plugin-ofset-*.nbm
rem ==========================================================

set "PROJE=%~dp0"
set "SRC=%PROJE%ugs-src"
set "UGS_ETIKET=v2.1.26"

echo.
echo === 1/5 Arac kontrolu ===
where java >nul 2>&1
if errorlevel 1 (
    echo [HATA] Java bulunamadi. JDK 17 kur: https://adoptium.net
    echo        Kurulumda "Set JAVA_HOME" secenegini isaretle.
    goto :hata
)
java -version
where git >nul 2>&1
if errorlevel 1 (
    echo [HATA] Git bulunamadi. Kur: https://git-scm.com
    goto :hata
)

echo.
echo === 2/5 UGS %UGS_ETIKET% kaynak kodu ===
if exist "%SRC%\pom.xml" (
    echo Kaynak kod zaten var, indirme atlandi.
) else (
    git clone --depth 1 --branch %UGS_ETIKET% https://github.com/winder/Universal-G-Code-Sender.git "%SRC%"
    if errorlevel 1 goto :hata
)

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
