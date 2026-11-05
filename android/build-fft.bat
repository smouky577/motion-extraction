@echo off
cd /d "%~dp0"
set "JAVA_HOME=C:\Users\Pc\Projects\Ballistic calculator app\BallisticScope\.tools\jdk\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call gradlew.bat :fft:assembleDebug
if errorlevel 1 exit /b 1
if not exist dist mkdir dist
copy /Y fft\build\outputs\apk\debug\fft-debug.apk dist\TemporalFft.apk
echo APK: dist\TemporalFft.apk
