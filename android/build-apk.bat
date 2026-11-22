@echo off
cd /d "%~dp0"
set "JAVA_HOME=C:\Tools\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist keystore.properties (
    echo Липсва keystore.properties — release не се подписва.
    exit /b 1
)
call gradlew.bat assembleRelease "-Dorg.gradle.java.home=%JAVA_HOME%"
if errorlevel 1 exit /b 1
if not exist dist mkdir dist
copy /Y app\build\outputs\apk\release\app-release.apk dist\MotionExtraction.apk
echo APK: dist\MotionExtraction.apk
