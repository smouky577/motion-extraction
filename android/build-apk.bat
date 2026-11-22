@echo off
cd /d "%~dp0"
if not defined JAVA_HOME if exist "C:\Tools\jdk-17\bin\java.exe" set "JAVA_HOME=C:\Tools\jdk-17"
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_JAVA="
if defined JAVA_HOME set "GRADLE_JAVA=-Dorg.gradle.java.home=%JAVA_HOME%"

if exist keystore.properties (
    call gradlew.bat assembleRelease %GRADLE_JAVA%
    if errorlevel 1 exit /b 1
    if not exist dist mkdir dist
    copy /Y app\build\outputs\apk\release\app-release.apk dist\MotionExtraction.apk
) else (
    echo Няма keystore.properties. Билдва се debug APK.
    call gradlew.bat assembleDebug %GRADLE_JAVA%
    if errorlevel 1 exit /b 1
    if not exist dist mkdir dist
    copy /Y app\build\outputs\apk\debug\app-debug.apk dist\MotionExtraction.apk
)
echo APK: dist\MotionExtraction.apk
