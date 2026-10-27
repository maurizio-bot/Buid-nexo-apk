@echo off
rem Arranca el servidor de Ultra Yo en Windows. Doble clic para ejecutarlo.
cd /d "%~dp0"

if not exist .venv (
    echo Preparando Python por primera vez...
    python -m venv .venv || goto :error
    .venv\Scripts\pip install -r requirements.txt || goto :error
)

if not exist token.txt (
    powershell -NoProfile -Command "[guid]::NewGuid().ToString('N') | Out-File -Encoding ascii -NoNewline token.txt"
)
set /p JARVIS_TOKEN=<token.txt

echo.
echo Token para la app del telefono: %JARVIS_TOKEN%
echo.
.venv\Scripts\uvicorn app.main:app --host 127.0.0.1 --port 8000
goto :eof

:error
echo Algo fallo. Comprueba que Python esta instalado y vuelve a intentarlo.
pause
