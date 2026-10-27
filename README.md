# Ultra Yo

Asistente personal de voz tipo Jarvis para Android, con Claude como cerebro.

- `android/`: la app del teléfono (Kotlin + Jetpack Compose). Pulsas, hablas y te responde en voz alta.
- `backend/`: el servidor (Python + FastAPI). Recibe la conversación, se la pasa a Claude y devuelve la respuesta. La clave de la API de Claude vive solo aquí, nunca en el teléfono.

## Fase 1: puesta en marcha

### 1. Servidor

Necesita dos variables de entorno:

| Variable | Qué es |
|---|---|
| `ANTHROPIC_API_KEY` | Tu clave de la API de Claude (console.anthropic.com). |
| `JARVIS_TOKEN` | Una contraseña larga que inventes tú. La app la usa para que nadie más pueda usar tu servidor. |

Opcionales: `CLAUDE_MODEL` (por defecto `claude-opus-5-5`) y `CLAUDE_EFFORT` (por defecto `low`, para que responda rápido por voz).

Para desplegarlo en Railway: crea un proyecto desde este repositorio, pon `backend` como directorio raíz (usa el `Dockerfile`), añade las dos variables y genera un dominio público. Comprueba que `https://TU-DOMINIO/health` responde `{"ok": true}`.

En local:

```bash
cd backend
pip install -r requirements-dev.txt
ANTHROPIC_API_KEY=... JARVIS_TOKEN=... uvicorn app.main:app --reload
python -m pytest -q
```

### 2. App

Cada cambio compila el APK automáticamente en GitHub Actions (pestaña **Actions**, ejecución más reciente, artefacto `ultra-yo-apk`). Descárgalo en el teléfono, descomprímelo e instálalo (Android pedirá permiso para instalar apps de origen desconocido).

Al abrirla por primera vez, en **Ajustes** pon la dirección del servidor y el `JARVIS_TOKEN`.

### 3. Abrirla con el botón lateral (Samsung)

Ajustes → Funciones avanzadas → Botón lateral → Pulsar dos veces → Abrir app → Ultra Yo.

La app se muestra encima de la pantalla de bloqueo y empieza a escuchar sola. Con el teléfono bloqueado los ajustes quedan ocultos.

## Próximas fases

2. Palabra de activación ("Jarvis") con la pantalla bloqueada, sin tocar nada.
3. Herramientas para tus proyectos: GitHub, código, notas.
4. Control del teléfono y memoria a largo plazo.
