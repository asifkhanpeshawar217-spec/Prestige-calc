@echo off
cd /d "%~dp0"
py companion.py
if errorlevel 1 (
  echo.
  echo Could not start Prestige Companion. Make sure Python 3.10+ is installed.
  pause
)
