@echo off
setlocal

cd /d "%~dp0"

set SOURCE_APK=android\app\build\outputs\apk\debug\app-debug.apk
set DIST_DIR=dist
set OUTPUT_APK=%DIST_DIR%\com.venjsx.mobile-debug.apk

if not exist "android\gradlew.bat" (
  echo ERROR: android\gradlew.bat not found.
  exit /b 1
)

echo [1/3] Building debug APK...
call android\gradlew.bat -p android assembleDebug
if errorlevel 1 (
  echo ERROR: Gradle build failed.
  echo NOTE: Ensure Java and Gradle wrapper files are available.
  exit /b 1
)

echo [2/3] Creating shareable APK...
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
copy /Y "%SOURCE_APK%" "%OUTPUT_APK%" >nul
if errorlevel 1 (
  echo ERROR: Could not copy APK to %OUTPUT_APK%
  exit /b 1
)

echo [3/3] Done.
echo Share this file to your phone:
echo %CD%\%OUTPUT_APK%
endlocal
