@echo off
cd /d "%~dp0"
python "%~dp0tools\vtc_map.py"
if errorlevel 1 pause
