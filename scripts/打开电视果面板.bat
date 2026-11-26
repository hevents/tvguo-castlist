@echo off
chcp 936 >nul
title TVGuo Panel Launcher
echo ==================================================
echo   Find TVGuo on LAN and open the web panel
echo ==================================================
echo.
echo No admin rights, no hosts change, no Python needed.
echo You can also pass an IP: open_panel.ps1 -Ip 192.168.x.x
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0open_panel.ps1"
echo.
echo Log file: %~dp0open_panel.log
pause
