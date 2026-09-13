@echo off
setlocal EnableExtensions

rem One-time setup: install Android emulator + system image, create 2 AVDs.
rem Requires network. Can take several minutes / ~1–2 GB download.

set "SDK=C:\Android\Sdk"
set "JAVA_HOME=C:\Users\Hand 5 Team\tools\jdk-17"
set "SDKMANAGER=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
set "AVDMANAGER=%SDK%\cmdline-tools\latest\bin\avdmanager.bat"
set "EMULATOR=%SDK%\emulator\emulator.exe"
set "AVD1=caravan_phone_1"
set "AVD2=caravan_phone_2"
set "SYSIMG=system-images;android-34;google_apis;x86_64"

set "PATH=%JAVA_HOME%\bin;%SDK%\cmdline-tools\latest\bin;%SDK%\platform-tools;%PATH%"
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"

echo.
echo === Caravan: setup dual Android emulators ===
echo.

if not exist "%SDKMANAGER%" (
  echo ERROR: sdkmanager not found at "%SDKMANAGER%"
  goto :fail
)

echo [1/3] Installing emulator + Android 34 system image...
echo      (accept licenses; download may take a while)
call "%SDKMANAGER%" --sdk_root=%SDK% --install "emulator" "%SYSIMG%" "platforms;android-34"
if errorlevel 1 (
  echo Trying with auto-yes...
  echo y| call "%SDKMANAGER%" --sdk_root=%SDK% --install "emulator" "%SYSIMG%" "platforms;android-34"
)
if not exist "%EMULATOR%" (
  echo ERROR: emulator.exe still missing after install.
  goto :fail
)

echo.
echo [2/3] Accepting licenses...
echo y| call "%SDKMANAGER%" --sdk_root=%SDK% --licenses >nul 2>&1

echo.
echo [3/3] Creating AVDs %AVD1% and %AVD2%...
"%EMULATOR%" -list-avds 2>nul | findstr /x "%AVD1%" >nul
if errorlevel 1 (
  echo no | call "%AVDMANAGER%" create avd -n %AVD1% -k "%SYSIMG%" -d pixel_6 --force
) else (
  echo   %AVD1% already exists
)

"%EMULATOR%" -list-avds 2>nul | findstr /x "%AVD2%" >nul
if errorlevel 1 (
  echo no | call "%AVDMANAGER%" create avd -n %AVD2% -k "%SYSIMG%" -d pixel_6 --force
) else (
  echo   %AVD2% already exists
)

echo.
echo AVDs:
"%EMULATOR%" -list-avds
echo.
echo Setup done. Now run: run-dual-emulators.bat
pause
exit /b 0

:fail
echo.
echo Failed.
pause
exit /b 1
