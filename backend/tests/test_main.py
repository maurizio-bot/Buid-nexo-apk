from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app import main

AUTH = {"Authorization": "Bearer secreto"}


class FakeMessages:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


@pytest.fixture
def fake(monkeypatch):
    monkeypatch.setenv("JARVIS_TOKEN", "secreto")
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


client = TestClient(main.app)


def test_health():
    assert client.get("/health").json() == {"ok": True}


def test_rejects_wrong_token(fake):
    r = client.post("/chat", json={"messages": [{"role": "user", "content": "hola"}]},
                    headers={"Authorization": "Bearer otro"})
    assert r.status_code == 401
    assert fake.calls == []


def test_chat_returns_text_only(fake):
    r = client.post("/chat", json={"messages": [{"role": "user", "content": "hola"}]}, headers=AUTH)
    assert r.status_code == 200
    assert r.json() == {"reply": "Hola, Maurizio."}
    call = fake.calls[0]
    assert call["model"] == "claude-opus-5-5"
    assert call["messages"] == [{"role": "user", "content": "hola"}]
    assert call["fallbacks"] == "default"


def test_drops_leading_assistant_turns(fake):
    body = {"messages": [
        {"role": "assistant", "content": "¿En qué te ayudo?"},
        {"role": "user", "content": "hola"},
    ]}
    client.post("/chat", json=body, headers=AUTH)
    assert fake.calls[0]["messages"] == [{"role": "user", "content": "hola"}]


def test_last_turn_must_be_user(fake):
    body = {"messages": [
        {"role": "user", "content": "hola"},
        {"role": "assistant", "content": "hola"},
    ]}
    assert client.post("/chat", json=body, headers=AUTH).status_code == 400


def test_refusal(fake):
    fake.response.stop_reason = "refusal"
    r = client.post("/chat", json={"messages": [{"role": "user", "content": "x"}]}, headers=AUTH)
    assert r.json()["reply"].startswith("Lo siento")
