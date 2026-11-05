@echo off
cd /d "%~dp0"
if exist "C:\Python314\pythonw.exe" (
  start "" "C:\Python314\pythonw.exe" "%~dp0temporal_app.py"
  exit /b 0
)
echo Python 3.14 was not found at C:\Python314\pythonw.exe
pause
