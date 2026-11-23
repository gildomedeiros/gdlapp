@echo off
cd /d "%~dp0"
where python >nul 2>nul
if errorlevel 1 (
    py -3 "%~dp0fm_tools.py"
) else (
    python "%~dp0fm_tools.py"
)
if errorlevel 1 pause
