@echo off
setlocal EnableExtensions

rem Build debug APK (with Neshan key) and install+launch on the phone.
rem Double-click this file, or run from a terminal.

set "ROOT=%~dp0"
set "MOBILE=%ROOT%mobile"
set "DEFINES=%MOBILE%\dart_defines.json"
set "APK=%MOBILE%\build\app\outputs\flutter-apk\app-debug.apk"
set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
set "PACKAGE=com.caravan.app"
set "ACTIVITY=com.caravan.app/.MainActivity"

rem Prefer a specific device if connected; otherwise first authorized device.
set "DEVICE=cyovbmxcuwqgmrsk"

set "PATH=C:\flutter\bin;C:\Users\Hand 5 Team\tools\jdk-17\bin;C:\Android\Sdk\platform-tools;%PATH%"

echo.
echo === Caravan: build + install to phone ===
echo.

if not exist "%DEFINES%" (
  echo ERROR: Missing "%DEFINES%"
  echo Copy mobile\dart_defines.example.json to mobile\dart_defines.json
  echo and put your NESHAN_API_KEY there.
  goto :fail
)

if not exist "%ADB%" (
  echo ERROR: adb not found at "%ADB%"
  goto :fail
)

"%ADB%" start-server >nul 2>&1

"%ADB%" -s %DEVICE% get-state 2>nul | findstr /i "device" >nul
if errorlevel 1 (
  echo Device %DEVICE% not ready. Looking for any connected device...
  for /f "skip=1 tokens=1" %%D in ('"%ADB%" devices') do (
    if not "%%D"=="" if not "%%D"=="List" (
      set "DEVICE=%%D"
      goto :have_device
    )
  )
  echo ERROR: No Android device connected. Enable USB debugging and reconnect.
  goto :fail
)

:have_device
echo Using device: %DEVICE%
echo.

cd /d "%MOBILE%"
if errorlevel 1 goto :fail

echo [1/3] flutter build apk --debug --dart-define-from-file=dart_defines.json
call flutter build apk --debug --dart-define-from-file=dart_defines.json
if errorlevel 1 (
  echo ERROR: Flutter build failed.
  goto :fail
)

if not exist "%APK%" (
  echo ERROR: APK not found at "%APK%"
  goto :fail
)

echo.
echo [2/3] adb install -r
"%ADB%" -s %DEVICE% install -r "%APK%"
if errorlevel 1 (
  echo ERROR: Install failed.
  goto :fail
)

echo.
echo [3/3] launching %ACTIVITY%
"%ADB%" -s %DEVICE% shell am start -n %ACTIVITY%
if errorlevel 1 (
  echo WARNING: Install OK but launch failed. Open Caravan manually on the phone.
)

echo.
echo Done.
pause
exit /b 0

:fail
echo.
echo Failed.
pause
exit /b 1
