"""API SSE 集成测试，包括实际 ASGI http.disconnect（不缓冲整条响应）。"""

from __future__ import annotations

import asyncio
import itertools
import json
import os
import threading
from types import SimpleNamespace

import jwt as pyjwt
import pytest
from fastapi.testclient import TestClient

from dsh_bridge import service as service_module
from dsh_bridge.runtime import RuntimeBusyError
from entry.Conversrtion import AgentConversation
from entry.UserAiConfig import UserAiConfig
from mapper import AgentMessageMapper
from util import ApiKeyCrypto
from util.error_codes import AgentErrorCode
from util.jwt import get_java_jwt_algorithm

USER_ID = 321
_IDS = itertools.count(20001)


class FakeRuntime:
    def __init__(self, behavior="ok"):
        self.behavior = behavior
        self.cancel_event = None
        self.finished = threading.Event()

    def run_turn(self, question, token, on_event, *, cancel_event):
        self.cancel_event = cancel_event
        try:
            if self.behavior == "error":
                raise RuntimeError("connection refused; private detail")
            on_event({"type": "assistant/chunk", "data": {"text": "hello"}})
            if self.behavior == "blocking":
                assert cancel_event.wait(2), "HTTP disconnect failed to reach runtime"
            return SimpleNamespace(final_response="最终回答", finish_reason="completed")
        finally:
            self.finished.set()


class FakeManager:
    def __init__(self, runtime=None, *, busy=False):
        self.runtime = runtime or FakeRuntime()
        self.busy = busy
        self.owner = object()
        self.drops = []
        self.releases = []
        self.released = threading.Event()

    def claim_turn(self, conversation_id):
        if self.busy:
            raise RuntimeBusyError("busy")
        return self.owner

    def release_turn(self, conversation_id, owner):
        self.releases.append((conversation_id, owner))
        self.released.set()

    def obtain(self, conversation_id, ai_config):
        return self.runtime

    def drop(self, conversation_id, *, invalidate_only=False):
        self.drops.append((conversation_id, invalidate_only))


@pytest.fixture()
def client(db_session, monkeypatch):
    conversation = AgentConversation(user_id=USER_ID, agent_conversation_name="api-test")
    conversation.agent_conversation_id = next(_IDS)
    config = UserAiConfig(
        user_id=USER_ID, group_name="default", model_names=["test-model"],
        ai_url="http://model.local/v1", api_key=ApiKeyCrypto().encrypt("test-key"),
    )
    config.user_ai_config_id = next(_IDS)
    db_session.add_all([conversation, config])
    db_session.commit()
    manager = FakeManager()
    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: manager)
    secret = os.environ["JWT_SECRET"]
    token = pyjwt.encode({"sub": str(USER_ID)}, secret, algorithm=get_java_jwt_algorithm(secret))
    from api.main import app
    with TestClient(app) as test_client:
        yield test_client, {"Authorization": f"Bearer {token}"}, conversation.agent_conversation_id, config.user_ai_config_id


def _request(client):
    test_client, headers, conversation_id, config_id = client
    return test_client.post(
        "/api/agent/stream", headers=headers,
        json={"user_config_id": config_id, "conversation_id": conversation_id, "question": "hi"},
    )


def test_stream_endpoint_emits_sse_frames_and_commits_pair(client, db_session):
    response = _request(client)
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: session.event" in response.text
    assert '"type": "assistant/chunk"' in response.text
    assert "event: done" in response.text
    assert "最终回答" in response.text
    messages = AgentMessageMapper(db_session).get_all_agent_messages(client[2], USER_ID)
    assert sorted((m.role, m.content) for m in messages) == [("ASSISTANT", "最终回答"), ("USER", "hi")]


def test_stream_requires_jwt(client):
    test_client, _, conversation_id, config_id = client
    response = test_client.post(
        "/api/agent/stream",
        json={"user_config_id": config_id, "conversation_id": conversation_id, "question": "hi"},
    )
    assert response.status_code == 401


def test_busy_maps_to_error_without_drop_or_messages(client, db_session, monkeypatch):
    manager = FakeManager(busy=True)
    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: manager)
    response = _request(client)
    assert "event: error" in response.text
    assert AgentErrorCode.TURN_BUSY.message in response.text
    assert manager.drops == []
    assert manager.releases == []
    assert AgentMessageMapper(db_session).get_all_agent_messages(client[2], USER_ID) == []


def test_unreachable_config_returns_safe_error_and_closes_runtime(client, db_session, monkeypatch):
    manager = FakeManager(FakeRuntime("error"))
    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: manager)
    response = _request(client)
    assert "event: error" in response.text
    assert AgentErrorCode.AGENT_CALL_FAILED.message in response.text
    assert AgentErrorCode.RUNTIME_INTERRUPTED.message not in response.text
    assert "connection refused" not in response.text
    assert manager.drops == [(client[2], False)]
    assert manager.releases == [(client[2], manager.owner)]
    assert AgentMessageMapper(db_session).get_all_agent_messages(client[2], USER_ID) == []


async def _asgi_disconnect(app, headers, conversation_id, config_id):
    body = json.dumps({"user_config_id": config_id, "conversation_id": conversation_id, "question": "hi"}).encode()
    disconnect = asyncio.Event()
    sent = []
    request_sent = False
    disconnect_delivered = False

    async def receive():
        nonlocal request_sent, disconnect_delivered
        if not request_sent:
            request_sent = True
            return {"type": "http.request", "body": body, "more_body": False}
        await disconnect.wait()
        disconnect_delivered = True
        return {"type": "http.disconnect"}

    async def send(message):
        sent.append(message)
        if message["type"] == "http.response.body" and message.get("body"):
            # 首帧之后断开；不等待成功终止帧，也不消费完整响应。
            disconnect.set()
            await asyncio.sleep(0)

    scope = {
        "type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1", "method": "POST", "scheme": "http",
        "path": "/api/agent/stream", "raw_path": b"/api/agent/stream", "query_string": b"",
        "root_path": "", "server": ("testserver", 80), "client": ("127.0.0.1", 1234),
        "headers": [(b"content-type", b"application/json"),
                    (b"authorization", headers["Authorization"].encode())],
    }
    await asyncio.wait_for(app(scope, receive, send), timeout=4)
    assert disconnect_delivered, "test must actually deliver ASGI http.disconnect"
    return sent


def test_asgi_http_disconnect_cancels_producer_and_worker_without_messages(client, db_session, monkeypatch):
    runtime = FakeRuntime("blocking")
    manager = FakeManager(runtime)
    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: manager)
    from api.main import app

    sent = asyncio.run(_asgi_disconnect(app, client[1], client[2], client[3]))

    assert sent[0]["type"] == "http.response.start"
    assert sent[0]["status"] == 200
    chunks = b"".join(message.get("body", b"") for message in sent if message["type"] == "http.response.body")
    assert b"event: session.event" in chunks
    assert b"event: done" not in chunks
    assert runtime.cancel_event is not None and runtime.cancel_event.is_set()
    assert runtime.finished.wait(1)
    assert manager.released.wait(1)
    assert manager.drops == [(client[2], False)]
    assert manager.releases == [(client[2], manager.owner)]
    assert AgentMessageMapper(db_session).get_all_agent_messages(client[2], USER_ID) == []
    assert not any(t.name in {f"agent-stream-{client[2]}", f"dsh-turn-{client[2]}"} for t in threading.enumerate())
