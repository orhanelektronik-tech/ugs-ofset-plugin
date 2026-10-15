@echo off
setlocal EnableDelayedExpansion
rem ==========================================================
rem  UGS Ofset Kalibrasyonu eklentisi - otomatik derleme
rem  Hicbir sey kurmana gerek yok:
rem   - JDK yoksa tasinabilir Temurin JDK 17 bu klasore iner
rem   - Git yoksa UGS kaynagi zip olarak iner
rem   - Maven'i UGS'nin mvnw araci indirir
rem  Cikti: bu klasore ugs-platform-plugin-ofset-*.nbm
rem ==========================================================

set "PROJE=%~dp0"
set "SRC=%PROJE%ugs-src"
set "JDKDIR=%PROJE%jdk"
set "UGS_SURUM=2.1.26"
set "ZIP=%PROJE%ugs-kaynak.zip"
set "ZIP_URL=https://github.com/winder/Universal-G-Code-Sender/archive/refs/tags/v%UGS_SURUM%.zip"
set "JDK_ZIP=%PROJE%jdk17.zip"
set "JDK_URL=https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"

echo.
echo === 1/5 JDK (javac) araniyor ===
set "BULUNAN="

rem 1) JAVA_HOME bir JDK mi?
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "BULUNAN=%JAVA_HOME%"

rem 2) Daha once bu klasore indirilmis JDK var mi?
if not defined BULUNAN (
    for /d %%D in ("%JDKDIR%\*") do (
        if exist "%%~fD\bin\javac.exe" set "BULUNAN=%%~fD"
    )
)

rem 3) PATH icinde javac var mi?
if not defined BULUNAN (
    for /f "delims=" %%J in ('where javac 2^>nul') do (
        if not defined BULUNAN set "BULUNAN=%%~dpJ.."
    )
)

rem 4) Yoksa tasinabilir JDK 17 indir
if not defined BULUNAN (
    echo JDK bulunamadi. Tasinabilir Temurin JDK 17 indiriliyor ^(yaklasik 190 MB^)...
    powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%JDK_URL%' -OutFile '%JDK_ZIP%'"
    if errorlevel 1 goto :hata
    if not exist "%JDK_ZIP%" goto :hata
    echo JDK aciliyor...
    powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Expand-Archive -Path '%JDK_ZIP%' -DestinationPath '%JDKDIR%' -Force"
    if errorlevel 1 goto :hata
    del /Q "%JDK_ZIP%"
    for /d %%D in ("%JDKDIR%\*") do (
        if exist "%%~fD\bin\javac.exe" set "BULUNAN=%%~fD"
    )
)

if not defined BULUNAN (
    echo [HATA] JDK hazirlanamadi.
    goto :hata
)

for %%P in ("%BULUNAN%") do set "JAVA_HOME=%%~fP"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Kullanilan JDK: %JAVA_HOME%
"%JAVA_HOME%\bin\javac.exe" -version
if errorlevel 1 goto :hata

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

echo Git yok, zip olarak indiriliyor ^(yaklasik 60 MB^)...
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
set "AM=-am"
if exist "%USERPROFILE%\.m2\repository\com\willwinder\ugs-platform-ugscore" set "AM="
if defined AM (
    echo === 4/5 Ilk derleme: UGS parcalari + eklenti ^(10-20 dk surebilir^) ===
) else (
    echo === 4/5 Hizli derleme: sadece eklenti ===
)
if exist "%SRC%\ugs-platform\ugs-platform-plugin-ofset\target" rmdir /S /Q "%SRC%\ugs-platform\ugs-platform-plugin-ofset\target"
pushd "%SRC%"
call mvnw.cmd -B -DskipTests install -pl ugs-platform/ugs-platform-plugin-ofset %AM%
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
