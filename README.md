# Ultra Yo

Asistente personal de voz tipo Jarvis para Android, con tu propia IA como cerebro.

- `android/`: la app del teléfono (Kotlin + Jetpack Compose). Pulsas, hablas y te responde en voz alta.
- `backend/`: el servidor (Python + FastAPI). Recibe la conversación, se la pasa a la IA de tu PC y devuelve la respuesta.

## Puesta en marcha

Ultra Yo usa **tu propia IA**: un modelo abierto (por defecto Gemma 4 E2B de Google) que corre en tu PC con [Ollama](https://ollama.com). Es gratis, privado y no depende de nadie. El teléfono llega al PC desde cualquier sitio con [Tailscale](https://tailscale.com), una red privada gratuita, sin abrir nada a internet.

### 1. Servidor en tu PC (Windows)

1. Instala **Ollama** desde ollama.com y, en una terminal, descarga el modelo:
   ```
   ollama pull gemma4:e2b
   ```
   Es el modelo más ligero y funciona sin tarjeta gráfica dedicada. Si tu PC tiene una tarjeta gráfica con 8 GB o más, prueba `gemma4:e4b`, que es más listo. Para cambiar de modelo, define la variable `OLLAMA_MODEL`.
2. Instala **Python** desde python.org (marca "Add Python to PATH").
3. Descarga este repositorio (botón verde **Code** → **Download ZIP**) y descomprímelo.
4. Haz doble clic en `backend\iniciar.bat`. La primera vez instala lo necesario y te muestra el **token** que pondrás en la app. Deja esa ventana abierta.
5. Instala **Tailscale** en el PC y en el teléfono, con la misma cuenta. En una terminal del PC ejecuta:
   ```
   tailscale serve --bg 8000
   ```
   Te dará una dirección del tipo `https://tu-pc.algo.ts.net`. Esa es la **dirección del servidor** para la app.

Variables opcionales: `OLLAMA_URL` (por defecto `http://localhost:11434`), `OLLAMA_MODEL` (por defecto `gemma4:e2b`).

#### Usar Claude en vez de tu IA (opcional, de pago)

Con `LLM_PROVIDER=claude` y `ANTHROPIC_API_KEY` el servidor usa Claude (`CLAUDE_MODEL`, por defecto `claude-opus-5-5`; `CLAUDE_EFFORT`, por defecto `low`). También se puede desplegar en la nube con el `Dockerfile`.

#### Desarrollo

```bash
cd backend
pip install -r requirements-dev.txt
python -m pytest -q
```

### 2. App

Cada cambio compila el APK automáticamente en GitHub Actions (pestaña **Actions**, ejecución más reciente, artefacto `ultra-yo-apk`). Descárgalo en el teléfono, descomprímelo e instálalo (Android pedirá permiso para instalar apps de origen desconocido).

Al abrirla por primera vez, en **Ajustes** pon la dirección de Tailscale y el token. Tailscale tiene que estar activo en el teléfono.

### 3. Abrirla con el botón lateral (Samsung)

Ajustes → Funciones avanzadas → Botón lateral → Pulsar dos veces → Abrir app → Ultra Yo.

La app se muestra encima de la pantalla de bloqueo y empieza a escuchar sola. Con el teléfono bloqueado los ajustes quedan ocultos.

## Próximas fases

2. Palabra de activación ("Jarvis") con la pantalla bloqueada, sin tocar nada.
3. Herramientas para tus proyectos: GitHub, código, notas.
4. Control del teléfono y memoria a largo plazo.
