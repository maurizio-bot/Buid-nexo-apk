"""Servidor de Ultra Yo: recibe lo que dices en el teléfono y se lo pasa a la IA.

Por defecto usa tu propia IA: un modelo abierto que corre en tu PC con Ollama.
Opcionalmente puede usar Claude (de pago, por uso).

Variables de entorno:
  JARVIS_TOKEN       contraseña compartida con la app del teléfono (obligatoria)
  LLM_PROVIDER       ollama (por defecto) | claude
  OLLAMA_URL         dirección de Ollama (por defecto http://localhost:11434)
  OLLAMA_MODEL       modelo de Ollama (por defecto gemma4:e2b)
  ANTHROPIC_API_KEY  clave de la API de Claude (solo con LLM_PROVIDER=claude)
  CLAUDE_MODEL       modelo de Claude (por defecto claude-opus-5-5)
  CLAUDE_EFFORT      low | medium | high | xhigh | max (por defecto low, para responder rápido por voz)
"""

import os
import re
import secrets
from typing import Literal

import anthropic
import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

PROVIDER = os.environ.get("LLM_PROVIDER", "ollama")
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "gemma4:e2b")
CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL", "claude-opus-5-5")
CLAUDE_EFFORT = os.environ.get("CLAUDE_EFFORT", "low")
MAX_HISTORY = 40

SYSTEM_PROMPT = """Eres Ultra Yo, el asistente personal de Maurizio, al estilo de Jarvis.
Le ayudas sobre todo a desarrollar sus proyectos de software.
Hablas en español, con un tono cercano y directo.
Tus respuestas se leen en voz alta: sé breve (dos o tres frases salvo que te pida más),
no uses Markdown, listas, tablas ni bloques de código, y escribe los números y símbolos
como se dirían en voz alta."""

app = FastAPI(title="Ultra Yo")
_claude: anthropic.Anthropic | None = None
_http: httpx.Client | None = None


def get_client() -> anthropic.Anthropic:
    global _claude
    if _claude is None:
        _claude = anthropic.Anthropic()
    return _claude


def get_http() -> httpx.Client:
    global _http
    if _http is None:
        # Un modelo local puede tardar en cargarse la primera vez.
        _http = httpx.Client(timeout=httpx.Timeout(300, connect=5))
    return _http


def check_token(authorization: str = Header(default="")) -> None:
    expected = os.environ.get("JARVIS_TOKEN")
    if not expected:
        raise HTTPException(500, "JARVIS_TOKEN no está configurado en el servidor")
    if not secrets.compare_digest(authorization, f"Bearer {expected}"):
        raise HTTPException(401, "Token incorrecto")


class Turn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=20_000)


class ChatRequest(BaseModel):
    messages: list[Turn] = Field(min_length=1)


class ChatResponse(BaseModel):
    reply: str


def ask_ollama(messages: list[dict]) -> str:
    try:
        r = get_http().post(
            f"{OLLAMA_URL.rstrip('/')}/api/chat",
            json={
                "model": OLLAMA_MODEL,
                "messages": [{"role": "system", "content": SYSTEM_PROMPT}, *messages],
                "stream": False,
            },
        )
    except httpx.HTTPError:
        raise HTTPException(502, "No pude hablar con Ollama. ¿Está abierto en el PC?")
    if r.status_code == 404:
        raise HTTPException(502, f"Ollama no tiene el modelo {OLLAMA_MODEL}. Descárgalo con: ollama pull {OLLAMA_MODEL}")
    if r.status_code != 200:
        raise HTTPException(502, f"Error de Ollama ({r.status_code})")
    text = r.json().get("message", {}).get("content", "")
    # Algunos modelos razonan en voz alta entre etiquetas <think>; eso no se lee.
    return re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()


def ask_claude(messages: list[dict]) -> str:
    try:
        response = get_client().beta.messages.create(
            model=CLAUDE_MODEL,
            max_tokens=16000,
            system=SYSTEM_PROMPT,
            output_config={"effort": CLAUDE_EFFORT},
            messages=messages,
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
        )
    except anthropic.AuthenticationError:
        raise HTTPException(502, "La clave de la API de Claude no es válida")
    except anthropic.RateLimitError:
        raise HTTPException(503, "Claude está saturado, inténtalo en un momento")
    except anthropic.APIStatusError as e:
        raise HTTPException(502, f"Error de la API de Claude: {e.message}")
    except anthropic.APIConnectionError:
        raise HTTPException(502, "No se pudo conectar con Claude")

    if response.stop_reason == "refusal":
        return "Lo siento, con eso no puedo ayudarte."
    return "".join(b.text for b in response.content if b.type == "text").strip()


@app.get("/health")
def health() -> dict:
    return {"ok": True, "provider": PROVIDER}


@app.post("/chat", response_model=ChatResponse, dependencies=[Depends(check_token)])
def chat(req: ChatRequest) -> ChatResponse:
    history = req.messages[-MAX_HISTORY:]
    # La conversación tiene que empezar por el usuario.
    while history and history[0].role != "user":
        history = history[1:]
    if not history or history[-1].role != "user":
        raise HTTPException(400, "El último mensaje debe ser del usuario")

    messages = [t.model_dump() for t in history]
    text = ask_claude(messages) if PROVIDER == "claude" else ask_ollama(messages)
    return ChatResponse(reply=text or "No tengo respuesta para eso.")
