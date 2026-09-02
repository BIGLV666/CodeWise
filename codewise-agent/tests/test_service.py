"""DshAgentService：事件流、持久化与故障路径（运行时用替身，不启动子进程）。"""

from __future__ import annotations

from types import SimpleNamespace

import itertools

import pytest

from dsh_bridge import service as service_module
from dsh_bridge.runtime import AiRuntimeConfig, RuntimeBusyError
from dsh_bridge.sdk.errors import TransportClosedError
from dto.CallAiDto import CallAiDto
from entry.Conversrtion import AgentConversation
from entry.UserAiConfig import UserAiConfig
from mapper import AgentMessageMapper, UserAiConfigMapper
from util import ApiKeyCrypto

USER_ID = 101

# SQLite 文件库跨测试持久化，用递增 ID 避免主键冲突。
_IDS = itertools.count(7001)


class FakeRuntime:
    """DshConversationRuntime 替身：可编程的事件与失败行为。"""

    def __init__(self, behavior="ok"):
        self.behavior = behavior
        self.calls: list[tuple[str, str]] = []
        self.closed = False
        self.invalidated = False

    def matches(self, ai_config: AiRuntimeConfig) -> bool:
        return True

    def run_turn(self, question, token, on_event):
        self.calls.append((question, token))
        if self.behavior == "busy":
            raise RuntimeBusyError("busy")
        if self.behavior == "transport":
            raise TransportClosedError("runtime died")
        on_event({"type": "assistant/chunk", "data": {"text": "你好"}})
        if self.behavior == "empty":
            return SimpleNamespace(final_response="", finish_reason="completed")
        return SimpleNamespace(final_response="完整回答", finish_reason="completed")

    def close(self):
        self.closed = True

    def invalidate(self):
        self.invalidated = True


class FakeManager:
    def __init__(self, runtime: FakeRuntime):
        self.runtime = runtime
        self.drops: list[tuple[int, bool]] = []

    def obtain(self, conversation_id, ai_config):
        return self.runtime

    def drop(self, conversation_id, *, invalidate_only=False):
        self.drops.append((conversation_id, invalidate_only))


@pytest.fixture()
def seeded(db_session):
    """预置一个用户、一个对话和一份 AI 配置；返回 conversation_id。"""

    from entry.database import Base  # noqa: F401 - 确保模型已注册

    from entry.AgentMessage import AgentMessage  # noqa: F401

    conversation = AgentConversation(
        user_id=USER_ID,
        agent_conversation_name="测试对话",
    )
    # SQLite 不会为 BigInteger 主键自动生成 rowid 别名，且文件库跨测试持久，
    # 因此显式分配递增主键。
    conversation.agent_conversation_id = next(_IDS)
    db_session.add(conversation)
    config = UserAiConfig(
        user_id=USER_ID,
        group_name="default",
        model_names=["test-model"],
        ai_url="http://model.local/v1",
        api_key=ApiKeyCrypto().encrypt("sk-test"),
    )
    config.user_ai_config_id = next(_IDS)
    db_session.add(config)
    db_session.flush()
    db_session.commit()
    return {
        "conversation_id": conversation.agent_conversation_id,
        "config_id": config.user_ai_config_id,
    }


def _dto(seeded, question="帮我看看最近的提交", model=None):
    return CallAiDto(
        user_config_id=seeded["config_id"],
        user_id=USER_ID,
        question=question,
        conversation_id=seeded["conversation_id"],
        model_name=model,
    )


def _run(service, dto, token="tok"):
    return list(service.stream_turn(dto, token))


def test_happy_path_streams_events_and_persists(db_session, seeded, monkeypatch):
    runtime = FakeRuntime()
    manager = FakeManager(runtime)
    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: manager)

    frames = _run(service_module.DshAgentService(db_session), _dto(seeded))

    assert frames[0][0] == "session.event"
    assert frames[0][1]["event"]["type"] == "assistant/chunk"
    assert frames[0][1]["conversation_id"] == seeded["conversation_id"]
    assert frames[-1][0] == "done"
    assert frames[-1][1]["content"] == "完整回答"
    assert frames[-1][1]["finish_reason"] == "completed"
    # token 原样传给运行时（工具经 token 文件使用）
    assert runtime.calls[0][1] == "tok"

    mapper = AgentMessageMapper(db_session)
    roles = sorted(m.role for m in mapper.get_all_agent_messages(seeded["conversation_id"], USER_ID))
    assert roles == ["ASSISTANT", "USER"]


def test_busy_returns_error_frame_without_assistant_row(db_session, seeded, monkeypatch):
    runtime = FakeRuntime(behavior="busy")
    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: FakeManager(runtime))

    frames = _run(service_module.DshAgentService(db_session), _dto(seeded))

    assert frames[-1][0] == "error"
    assert "稍候" in frames[-1][1]["message"]
    mapper = AgentMessageMapper(db_session)
    roles = [m.role for m in mapper.get_all_agent_messages(seeded["conversation_id"], USER_ID)]
    assert roles == ["USER"]


def test_transport_error_drops_runtime_invalid_only(db_session, seeded, monkeypatch):
    runtime = FakeRuntime(behavior="transport")
    manager = FakeManager(runtime)
    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: manager)

    frames = _run(service_module.DshAgentService(db_session), _dto(seeded))

    assert frames[-1][0] == "error"
    assert manager.drops == [(seeded["conversation_id"], True)]


def test_empty_final_response_is_an_error(db_session, seeded, monkeypatch):
    runtime = FakeRuntime(behavior="empty")
    manager = FakeManager(runtime)
    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: manager)

    frames = _run(service_module.DshAgentService(db_session), _dto(seeded))

    assert frames[-1][0] == "error"
    assert manager.drops == [(seeded["conversation_id"], True)]


def test_invalid_model_name_rejected(db_session, seeded):
    svc = service_module.DshAgentService(db_session)
    with pytest.raises(ValueError):
        list(svc.stream_turn(_dto(seeded, model="other-model"), "tok"))


def test_foreign_conversation_rejected(db_session, seeded):
    svc = service_module.DshAgentService(db_session)
    dto = _dto(seeded)
    dto.user_id = 999
    with pytest.raises(LookupError):
        list(svc.stream_turn(dto, "tok"))


def test_ai_config_resolved_and_decrypted(db_session, seeded):
    svc = service_module.DshAgentService(db_session)
    dto = _dto(seeded)
    config = svc._resolve_ai_config(dto)
    assert isinstance(config, AiRuntimeConfig)
    assert config.model_name == "test-model"
    assert config.api_key == "sk-test"
    assert config.ai_url == "http://model.local/v1"
