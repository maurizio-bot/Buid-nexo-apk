import json
from types import SimpleNamespace

import httpx
import pytest
from fastapi.testclient import TestClient

from app import main

AUTH = {"Authorization": "Bearer secreto"}
HOLA = {"messages": [{"role": "user", "content": "hola"}]}

client = TestClient(main.app)


@pytest.fixture(autouse=True)
def token(monkeypatch):
    monkeypatch.setenv("JARVIS_TOKEN", "secreto")


class FakeMessages:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


@pytest.fixture
def claude(monkeypatch):
    monkeypatch.setattr(main, "PROVIDER", "claude")
    response = SimpleNamespace(
        stop_reason="end_turn",
        content=[
            SimpleNamespace(type="thinking", thinking=""),
            SimpleNamespace(type="text", text="Hola, Maurizio."),
        ],
    )
    messages = FakeMessages(response)
    monkeypatch.setattr(
        main, "get_client", lambda: SimpleNamespace(beta=SimpleNamespace(messages=messages))
    )
    return messages


@pytest.fixture
def ollama(monkeypatch):
    state = {"status": 200, "content": "Hola desde tu PC.", "requests": []}

    def handler(request: httpx.Request) -> httpx.Response:
        state["requests"].append(request)
        if state["status"] == "down":
            raise httpx.ConnectError("refused")
        return httpx.Response(state["status"], json={"message": {"role": "assistant", "content": state["content"]}})

    http = httpx.Client(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(main, "get_http", lambda: http)
    return state


def test_health():
    assert client.get("/health").json() == {"ok": True, "provider": "ollama"}


def test_rejects_wrong_token(ollama):
    r = client.post("/chat", json=HOLA, headers={"Authorization": "Bearer otro"})
    assert r.status_code == 401
    assert ollama["requests"] == []


def test_ollama_is_default(ollama):
    r = client.post("/chat", json=HOLA, headers=AUTH)
    assert r.json() == {"reply": "Hola desde tu PC."}
    req = ollama["requests"][0]
    assert req.url == "http://localhost:11434/api/chat"
    body = json.loads(req.content)
    assert body["model"] == "gemma4:e2b"
    assert body["stream"] is False
    assert body["messages"][0]["role"] == "system"
    assert body["messages"][1:] == [{"role": "user", "content": "hola"}]


def test_ollama_strips_thinking(ollama):
    ollama["content"] = "<think>a ver...</think>\nSon las tres."
    assert client.post("/chat", json=HOLA, headers=AUTH).json() == {"reply": "Son las tres."}


def test_ollama_missing_model(ollama):
    ollama["status"] = 404
    r = client.post("/chat", json=HOLA, headers=AUTH)
    assert r.status_code == 502
    assert "ollama pull gemma4:e2b" in r.json()["detail"]


def test_ollama_down(ollama):
    ollama["status"] = "down"
    r = client.post("/chat", json=HOLA, headers=AUTH)
    assert r.status_code == 502
    assert "Ollama" in r.json()["detail"]


def test_claude_returns_text_only(claude):
    r = client.post("/chat", json=HOLA, headers=AUTH)
    assert r.json() == {"reply": "Hola, Maurizio."}
    call = claude.calls[0]
    assert call["model"] == "claude-opus-5-5"
    assert call["messages"] == [{"role": "user", "content": "hola"}]
    assert call["fallbacks"] == "default"


def test_claude_refusal(claude):
    claude.response.stop_reason = "refusal"
    assert client.post("/chat", json=HOLA, headers=AUTH).json()["reply"].startswith("Lo siento")


def test_drops_leading_assistant_turns(ollama):
    body = {"messages": [
        {"role": "assistant", "content": "¿En qué te ayudo?"},
        {"role": "user", "content": "hola"},
    ]}
    client.post("/chat", json=body, headers=AUTH)
    assert json.loads(ollama["requests"][0].content)["messages"][1:] == [{"role": "user", "content": "hola"}]


def test_last_turn_must_be_user(ollama):
    body = {"messages": [
        {"role": "user", "content": "hola"},
        {"role": "assistant", "content": "hola"},
    ]}
    assert client.post("/chat", json=body, headers=AUTH).status_code == 400
