@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Build debug APK for the native Kotlin :app and install+launch on the phone.
rem Double-click this file, or run from a terminal.

set "ROOT=%~dp0"
set "APP_DIR=%ROOT%app"
set "APK=%APP_DIR%\build\outputs\apk\debug\app-debug.apk"
set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
set "SDK_DIR=C:\Android\Sdk"
set "PACKAGE=com.aistudio.caravan.kqzvpm"
set "ACTIVITY=com.aistudio.caravan.kqzvpm/com.example.MainActivity"
set "GRADLE=C:\Users\Hand 5 Team\.gradle\wrapper\dists\gradle-9.3.1-all\9ot9r568e8zfvvd4mn8rbu1j0\gradle-9.3.1\bin\gradle.bat"
set "KEYSTORE=%ROOT%debug.keystore"
set "DEFAULT_KEYSTORE=%USERPROFILE%\.android\debug.keystore"

rem Prefer a specific device if connected; otherwise first authorized device.
set "DEVICE=cyovbmxcuwqgmrsk"

rem Prefer JDK 21 if present (required by app compileOptions); fall back to JDK 17.
set "JAVA_HOME="
if exist "C:\Program Files\Eclipse Adoptium\jdk-21*" (
  for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do set "JAVA_HOME=%%~J"
)
if not defined JAVA_HOME if exist "C:\Users\Hand 5 Team\tools\jdk-21" set "JAVA_HOME=C:\Users\Hand 5 Team\tools\jdk-21"
if not defined JAVA_HOME if exist "C:\Users\Hand 5 Team\tools\jdk-17" set "JAVA_HOME=C:\Users\Hand 5 Team\tools\jdk-17"
if not defined JAVA_HOME if exist "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot" set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"

set "PATH=%JAVA_HOME%\bin;C:\Android\Sdk\platform-tools;%PATH%"

echo.
echo === Caravan Kotlin: build + install to phone ===
echo.

if not exist "%APP_DIR%\build.gradle.kts" (
  echo ERROR: Kotlin app module not found at "%APP_DIR%"
  goto :fail
)

if not exist "%ADB%" (
  echo ERROR: adb not found at "%ADB%"
  goto :fail
)

if not exist "%GRADLE%" (
  echo ERROR: Gradle 9.3.1 not found at:
  echo   %GRADLE%
  echo Open the project once in Android Studio to download Gradle, then retry.
  goto :fail
)

if not defined JAVA_HOME (
  echo ERROR: No JDK found. Install JDK 21 ^(preferred^) or JDK 17.
  goto :fail
)
echo Using JAVA_HOME: %JAVA_HOME%

rem Ensure Android SDK path for Gradle.
if not exist "%ROOT%local.properties" (
  > "%ROOT%local.properties" echo sdk.dir=C\:\\Android\\Sdk
  echo Created local.properties
)

rem Project expects debug.keystore in repo root.
if not exist "%KEYSTORE%" (
  if exist "%DEFAULT_KEYSTORE%" (
    copy /Y "%DEFAULT_KEYSTORE%" "%KEYSTORE%" >nul
    echo Copied debug.keystore from %USERPROFILE%\.android
  ) else (
    echo Generating debug.keystore...
    keytool -genkeypair -v -keystore "%KEYSTORE%" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    if errorlevel 1 (
      echo ERROR: Could not create debug.keystore
      goto :fail
    )
  )
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

cd /d "%ROOT%"
if errorlevel 1 goto :fail

echo [1/3] gradle :app:assembleDebug
call "%GRADLE%" :app:assembleDebug --no-daemon
if errorlevel 1 (
  echo.
  echo ERROR: Kotlin build failed.
  echo If the error mentions Java 21 / JVM target, install JDK 21 and re-run.
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
  echo Signature mismatch or install blocked. Uninstalling %PACKAGE% then retrying...
  "%ADB%" -s %DEVICE% uninstall %PACKAGE% >nul 2>&1
  "%ADB%" -s %DEVICE% install -r "%APK%"
  if errorlevel 1 (
    echo ERROR: Install failed.
    echo If you see INSTALL_FAILED_USER_RESTRICTED, enable "Install via USB"
    echo in Developer options and tap Allow on the phone prompt, then re-run.
    goto :fail
  )
)

echo.
echo [3/3] launching %ACTIVITY%
"%ADB%" -s %DEVICE% shell am start -n %ACTIVITY%
if errorlevel 1 (
  echo WARNING: Install OK but launch failed. Open the Kotlin Caravan app manually on the phone.
)

echo.
echo Done. Package: %PACKAGE%
echo Note: this app uses the grid map, not Flutter MapLibre.
pause
exit /b 0

:fail
echo.
echo Failed.
pause
exit /b 1
