@echo off
title Waypoint Vite Frontend
if exist "C:\Users\ultim\AppData\Local\JetBrains\IntelliJIdea2026.2\acp-agents\.runtimes\node\24.13.0" (
    set "PATH=C:\Users\ultim\AppData\Local\JetBrains\IntelliJIdea2026.2\acp-agents\.runtimes\node\24.13.0;%PATH%"
)
cd /d "%~dp0\frontend"
echo ==========================================================
echo Starting Waypoint Vite Frontend (Port 5173)
echo Proxying /api to http://127.0.0.1:8081
echo ==========================================================
call npm.cmd run dev
pause
