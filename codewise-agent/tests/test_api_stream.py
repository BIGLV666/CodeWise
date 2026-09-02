"""API 层集成测试：JWT 鉴权 → DshAgentService → SSE 帧（运行时用替身）。"""

from __future__ import annotations

import os
from types import SimpleNamespace

import jwt as pyjwt
import pytest
from fastapi.testclient import TestClient

from dsh_bridge import service as service_module
from dsh_bridge.runtime import RuntimeBusyError
from entry.Conversrtion import AgentConversation
from entry.UserAiConfig import UserAiConfig
from util import ApiKeyCrypto
from util.jwt import get_java_jwt_algorithm

USER_ID = 321
_IDS = __import__("itertools").count(20001)


class FakeRuntime:
    def run_turn(self, question, token, on_event):
        on_event({"type": "assistant/chunk", "data": {"text": "hello"}})
        return SimpleNamespace(final_response="最终回答", finish_reason="completed")


class FakeManager:
    def obtain(self, conversation_id, ai_config):
        return FakeRuntime()

    def drop(self, conversation_id, *, invalidate_only=False):
        pass


@pytest.fixture()
def client(db_session, monkeypatch):
    """预置数据 + 签发 JWT + 替换运行时管理器，返回 (TestClient, conversation_id, config_id)。"""

    conversation = AgentConversation(user_id=USER_ID, agent_conversation_name="api-test")
    conversation.agent_conversation_id = next(_IDS)
    config = UserAiConfig(
        user_id=USER_ID,
        group_name="default",
        model_names=["test-model"],
        ai_url="http://model.local/v1",
        api_key=ApiKeyCrypto().encrypt("test-key"),
    )
    config.user_ai_config_id = next(_IDS)
    db_session.add_all([conversation, config])
    db_session.commit()

    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: FakeManager())

    secret = os.environ["JWT_SECRET"]
    token = pyjwt.encode({"sub": str(USER_ID)}, secret, algorithm=get_java_jwt_algorithm(secret))
    from api.main import app

    test_client = TestClient(app)
    headers = {"Authorization": f"Bearer {token}"}
    return test_client, headers, conversation.agent_conversation_id, config.user_ai_config_id


def test_stream_endpoint_emits_sse_frames(client):
    test_client, headers, conversation_id, config_id = client
    response = test_client.post(
        "/api/agent/stream",
        json={"user_config_id": config_id, "conversation_id": conversation_id, "question": "hi"},
        headers=headers,
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: session.event" in response.text
    assert '"type": "assistant/chunk"' in response.text or '"type":"assistant/chunk"' in response.text
    assert "event: done" in response.text
    assert "最终回答" in response.text


def test_stream_requires_jwt(client):
    test_client, _, conversation_id, config_id = client
    response = test_client.post(
        "/api/agent/stream",
        json={"user_config_id": config_id, "conversation_id": conversation_id, "question": "hi"},
    )
    assert response.status_code == 401


def test_busy_maps_to_error_frame(client):
    test_client, headers, conversation_id, config_id = client

    class BusyRuntime:
        def run_turn(self, question, token, on_event):
            raise RuntimeBusyError("busy")

    class BusyManager:
        def obtain(self, conversation_id, ai_config):
            return BusyRuntime()

        def drop(self, conversation_id, *, invalidate_only=False):
            pass

    # 重新打桩为 busy 场景
    import dsh_bridge.service as svc

    original = svc.get_runtime_manager
    svc.get_runtime_manager = lambda: BusyManager()
    try:
        response = test_client.post(
            "/api/agent/stream",
            json={"user_config_id": config_id, "conversation_id": conversation_id, "question": "hi"},
            headers=headers,
        )
    finally:
        svc.get_runtime_manager = original
    assert "event: error" in response.text
    assert "稍候" in response.text


def test_unreachable_config_returns_error(client):
    """模型端点不可达时应得到 error 帧（真实运行时启动失败的兜底路径）。"""

    test_client, headers, conversation_id, config_id = client
    import dsh_bridge.service as svc

    class BoomRuntime:
        def run_turn(self, question, token, on_event):
            raise RuntimeError("connection refused")

    class BoomManager:
        def obtain(self, conversation_id, ai_config):
            # 强制走真实 DshConversationRuntime 的场景过重，这里直接模拟
            # 运行时启动/通信失败后的通用错误路径。
            return BoomRuntime()

        def drop(self, conversation_id, *, invalidate_only=False):
            pass

    original = svc.get_runtime_manager
    svc.get_runtime_manager = lambda: BoomManager()
    try:
        response = test_client.post(
            "/api/agent/stream",
            json={"user_config_id": config_id, "conversation_id": conversation_id, "question": "hi"},
            headers=headers,
        )
    finally:
        svc.get_runtime_manager = original
    assert "event: error" in response.text
