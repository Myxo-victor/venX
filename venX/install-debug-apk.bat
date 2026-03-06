@echo off
setlocal

cd /d "%~dp0"

set APP_ID=com.venjsx.mobile
set MAIN_ACTIVITY=com.venjsx.MainActivity
set APK_PATH=%~1

if "%APK_PATH%"=="" set APK_PATH=dist\com.venjsx.mobile-debug.apk
if not exist "%APK_PATH%" set APK_PATH=android\app\build\outputs\apk\debug\app-debug.apk

where adb >nul 2>nul
if errorlevel 1 (
  echo ERROR: adb not found in PATH. Add Android SDK platform-tools to PATH.
  exit /b 1
)

if not exist "%APK_PATH%" (
  echo ERROR: APK not found. Run package-debug-apk.bat first.
  exit /b 1
)

echo [1/3] Checking device connection...
adb get-state >nul 2>nul
if errorlevel 1 (
  echo ERROR: No adb device detected. Connect phone and enable USB debugging.
  exit /b 1
)

echo [2/3] Installing %APK_PATH%...
adb install -r "%APK_PATH%"
if errorlevel 1 (
  echo Install failed. Trying clean reinstall for signature/package conflicts...
  adb uninstall %APP_ID% >nul 2>nul
  adb install "%APK_PATH%"
  if errorlevel 1 (
    echo ERROR: Install failed after retry.
    exit /b 1
  )
)

echo [3/3] Launching app...
adb shell am start -n %APP_ID%/%MAIN_ACTIVITY%
if errorlevel 1 (
  echo ERROR: Launch command failed.
  exit /b 1
)

echo Done.
endlocal
