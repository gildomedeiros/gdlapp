@echo off
cd /d "%~dp0"
python "%~dp0tools\surfer_gps_map.py" --serve
if errorlevel 1 pause
