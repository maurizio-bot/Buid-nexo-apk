"""Servidor de Ultra Yo: recibe lo que dices en el teléfono y se lo pasa a Claude.

Variables de entorno:
  ANTHROPIC_API_KEY  clave de la API de Claude (obligatoria)
  JARVIS_TOKEN       contraseña compartida con la app del teléfono (obligatoria)
  CLAUDE_MODEL       modelo a usar (por defecto claude-opus-5-5)
  CLAUDE_EFFORT      low | medium | high | xhigh | max (por defecto low, para responder rápido por voz)
"""

import os
import secrets
from typing import Literal

import anthropic
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

MODEL = os.environ.get("CLAUDE_MODEL", "claude-opus-5-5")
EFFORT = os.environ.get("CLAUDE_EFFORT", "low")
MAX_HISTORY = 40

SYSTEM_PROMPT = """Eres Ultra Yo, el asistente personal de Maurizio, al estilo de Jarvis.
Le ayudas sobre todo a desarrollar sus proyectos de software.
Hablas en español, con un tono cercano y directo.
Tus respuestas se leen en voz alta: sé breve (dos o tres frases salvo que te pida más),
no uses Markdown, listas, tablas ni bloques de código, y escribe los números y símbolos
como se dirían en voz alta."""

app = FastAPI(title="Ultra Yo")
_client: anthropic.Anthropic | None = None


def get_client() -> anthropic.Anthropic:
    global _client
    if _client is None:
        _client = anthropic.Anthropic()
    return _client


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


@app.get("/health")
def health() -> dict:
    return {"ok": True}


@app.post("/chat", response_model=ChatResponse, dependencies=[Depends(check_token)])
def chat(req: ChatRequest) -> ChatResponse:
    history = req.messages[-MAX_HISTORY:]
    # La API exige que la conversación empiece por el usuario.
    while history and history[0].role != "user":
        history = history[1:]
    if not history or history[-1].role != "user":
        raise HTTPException(400, "El último mensaje debe ser del usuario")

    try:
        response = get_client().beta.messages.create(
            model=MODEL,
            max_tokens=16000,
            system=SYSTEM_PROMPT,
            output_config={"effort": EFFORT},
            messages=[t.model_dump() for t in history],
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
        return ChatResponse(reply="Lo siento, con eso no puedo ayudarte.")

    text = "".join(b.text for b in response.content if b.type == "text").strip()
    return ChatResponse(reply=text or "No tengo respuesta para eso.")
