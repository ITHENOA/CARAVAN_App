@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Run Caravan on TWO Android emulators (PC multi-device test).
rem Double-click, or run from a terminal.
rem
rem First time: run setup-emulators.bat once (downloads system image + creates AVDs).

set "ROOT=%~dp0"
set "MOBILE=%ROOT%mobile"
set "DEFINES=%MOBILE%\dart_defines.json"
set "SDK=C:\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMULATOR=%SDK%\emulator\emulator.exe"
set "AVD1=caravan_phone_1"
set "AVD2=caravan_phone_2"

set "PATH=C:\flutter\bin;C:\Users\Hand 5 Team\tools\jdk-17\bin;%SDK%\platform-tools;%SDK%\emulator;%PATH%"
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"

echo.
echo === Caravan: dual emulator test ===
echo.

if not exist "%DEFINES%" (
  echo ERROR: Missing "%DEFINES%"
  echo Copy mobile\dart_defines.example.json to mobile\dart_defines.json
  goto :fail
)

if not exist "%ADB%" (
  echo ERROR: adb not found at "%ADB%"
  goto :fail
)

if not exist "%EMULATOR%" (
  echo ERROR: emulator.exe not found.
  echo Run setup-emulators.bat first.
  goto :fail
)

"%EMULATOR%" -list-avds 2>nul | findstr /x "%AVD1%" >nul
if errorlevel 1 (
  echo ERROR: AVD "%AVD1%" missing. Run setup-emulators.bat first.
  goto :fail
)
"%EMULATOR%" -list-avds 2>nul | findstr /x "%AVD2%" >nul
if errorlevel 1 (
  echo ERROR: AVD "%AVD2%" missing. Run setup-emulators.bat first.
  goto :fail
)

"%ADB%" start-server >nul 2>&1

echo [1/4] Starting emulators...
start "emulator-%AVD1%" "%EMULATOR%" -avd %AVD1% -netdelay none -netspeed full
timeout /t 4 /nobreak >nul
start "emulator-%AVD2%" "%EMULATOR%" -avd %AVD2% -netdelay none -netspeed full

echo [2/4] Waiting for both emulators to boot (can take 1-2 min)...
call :wait_boot
if errorlevel 1 goto :fail

echo.
echo Emulators ready:
"%ADB%" devices -l
echo.

cd /d "%MOBILE%"
if errorlevel 1 goto :fail

rem Collect exactly two emulator serials (emulator-5554 / emulator-5556 ...)
set "E1="
set "E2="
for /f "tokens=1" %%D in ('"%ADB%" devices ^| findstr /r "emulator-[0-9]*[	 ]*device"') do (
  if not defined E1 (
    set "E1=%%D"
  ) else if not defined E2 (
    set "E2=%%D"
  )
)

if not defined E1 (
  echo ERROR: No emulator device online.
  goto :fail
)
if not defined E2 (
  echo ERROR: Only one emulator online. Need two.
  echo Tip: close other emulators and re-run, or start %AVD2% manually.
  goto :fail
)

echo [3/4] flutter run on %E1%
start "caravan-%E1%" cmd /k "cd /d "%MOBILE%" && flutter run -d %E1% --dart-define-from-file=dart_defines.json"

timeout /t 8 /nobreak >nul

echo [4/4] flutter run on %E2%
start "caravan-%E2%" cmd /k "cd /d "%MOBILE%" && flutter run -d %E2% --dart-define-from-file=dart_defines.json"

echo.
echo Done. Two Flutter windows should open — one per emulator.
echo Close those windows to stop the apps.
echo.
pause
exit /b 0

:wait_boot
set /a tries=0
:wait_loop
set /a tries+=1
if %tries% GTR 90 (
  echo ERROR: Timed out waiting for emulators.
  exit /b 1
)
set "count=0"
for /f "tokens=1" %%D in ('"%ADB%" devices ^| findstr /r "emulator-[0-9]*[	 ]*device"') do (
  "%ADB%" -s %%D shell getprop sys.boot_completed 2>nul | findstr /x "1" >nul
  if not errorlevel 1 set /a count+=1
)
if !count! GEQ 2 exit /b 0
echo   ... booted=!count!/2  (try !tries!/90)
timeout /t 4 /nobreak >nul
goto :wait_loop

:fail
echo.
echo Failed.
pause
exit /b 1
