"""DshAgentService：提交、失败、背压及取消生命周期（不启动子进程）。"""

from __future__ import annotations

import itertools
import logging
import queue
import threading
import traceback
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from dsh_bridge import service as service_module
from dsh_bridge.runtime import AiRuntimeConfig, RuntimeBusyError
from dsh_bridge.sdk.errors import TransportClosedError
from dto.CallAiDto import CallAiDto
from entry.Conversrtion import AgentConversation
from entry.UserAiConfig import UserAiConfig
from mapper import AgentMessageMapper
from util import ApiKeyCrypto
from util.error_codes import AgentErrorCode

USER_ID = 101
_IDS = itertools.count(7001)


class FakeRuntime:
    def __init__(self, behavior="ok"):
        self.behavior = behavior
        self.calls = []
        self.cancel_event = None
        self.finished = threading.Event()
        self.turn_timeout_seconds = 2

    def run_turn(self, question, token, on_event, *, cancel_event):
        self.calls.append((question, token))
        self.cancel_event = cancel_event
        try:
            if self.behavior == "busy":
                raise RuntimeBusyError("busy")
            if self.behavior == "transport":
                raise TransportClosedError("runtime died")
            if self.behavior == "error":
                raise RuntimeError("private downstream detail")
            if self.behavior == "silent":
                assert cancel_event.wait(2), "service failed to cancel stalled runtime"
                return SimpleNamespace(final_response="", finish_reason="cancelled")
            on_event({"type": "assistant/chunk", "data": {"text": "你好"}})
            if self.behavior == "blocking":
                assert cancel_event.wait(2), "service failed to cancel runtime"
            if self.behavior == "empty":
                return SimpleNamespace(final_response="", finish_reason="completed")
            if self.behavior == "upstream_error":
                # 复刻 dsh 的失败形状：单步 failure + turn/end 的 reason.error
                message = '503: {"code":"model_not_found","message":"No available channel for model x"}'
                return SimpleNamespace(
                    final_response="",
                    finish_reason="error",
                    events=[
                        {"type": "assistant/chunk",
                         "data": {"chunk": {"finish": {"reason": {"kind": "error",
                                                                 "failure": {"message": message}}}}}},
                        {"type": "turn/end",
                         "data": {"reason": {"kind": "error", "error": {"message": message}}}},
                    ],
                )
            return SimpleNamespace(
                final_response="完整回答",
                finish_reason="failed" if self.behavior == "incomplete" else "completed",
            )
        finally:
            self.finished.set()


class FakeManager:
    def __init__(self, runtime, *, busy=False):
        self.runtime = runtime
        self.busy = busy
        self.drops = []
        self.claims = []
        self.releases = []
        self.obtains = []
        self.owner = object()

    def claim_turn(self, conversation_id):
        self.claims.append(conversation_id)
        if self.busy:
            raise RuntimeBusyError("busy")
        return self.owner

    def release_turn(self, conversation_id, owner):
        self.releases.append((conversation_id, owner))

    def obtain(self, conversation_id, ai_config):
        self.obtains.append((conversation_id, ai_config))
        return self.runtime

    def drop(self, conversation_id, *, invalidate_only=False):
        self.drops.append((conversation_id, invalidate_only))


@pytest.fixture()
def seeded(db_session):
    conversation = AgentConversation(user_id=USER_ID, agent_conversation_name="测试对话")
    conversation.agent_conversation_id = next(_IDS)
    config = UserAiConfig(
        user_id=USER_ID, group_name="default", model_names=["test-model"],
        ai_url="http://model.local/v1", api_key=ApiKeyCrypto().encrypt("sk-test"),
    )
    config.user_ai_config_id = next(_IDS)
    db_session.add_all([conversation, config])
    db_session.commit()
    return {"conversation_id": conversation.agent_conversation_id, "config_id": config.user_ai_config_id}


def _dto(seeded, question="帮我看看最近的提交", model=None):
    return CallAiDto(
        user_config_id=seeded["config_id"], user_id=USER_ID, question=question,
        conversation_id=seeded["conversation_id"], model_name=model,
    )


def _messages(db_session, seeded):
    return AgentMessageMapper(db_session).get_all_agent_messages(seeded["conversation_id"], USER_ID)


def _install(monkeypatch, runtime, **kwargs):
    manager = FakeManager(runtime, **kwargs)
    monkeypatch.setattr(service_module, "get_runtime_manager", lambda: manager)
    return manager


def test_happy_path_commits_message_pair_before_done(db_session, seeded, monkeypatch):
    runtime = FakeRuntime()
    manager = _install(monkeypatch, runtime)
    commit = Mock(wraps=db_session.commit)
    monkeypatch.setattr(db_session, "commit", commit)
    turn = service_module.DshAgentService(db_session).stream_turn(_dto(seeded, question=" hi "), "tok")

    first = next(turn)
    assert first[0] == "session.event"
    assert first[1]["event"]["type"] == "assistant/chunk"
    assert first[1]["conversation_id"] == seeded["conversation_id"]
    assert _messages(db_session, seeded) == []
    done = next(turn)
    assert done[0] == "done"
    assert done[1]["content"] == "完整回答"
    assert done[1]["finish_reason"] == "completed"
    commit.assert_called_once_with()
    # 用另一个 Session 验证真正 commit，而不是仅在当前事务中可见。
    from entry.database import SessionLocal
    with SessionLocal() as observer:
        assert sorted((m.role, m.content) for m in _messages(observer, seeded)) == [
            ("ASSISTANT", "完整回答"), ("USER", "hi"),
        ]
    turn.close()
    assert runtime.calls == [("hi", "tok")]
    assert manager.drops == []
    assert manager.releases == [(seeded["conversation_id"], manager.owner)]


@pytest.mark.parametrize("busy_at", ["claim", "runtime"])
def test_busy_has_no_messages_and_does_not_drop(db_session, seeded, monkeypatch, busy_at):
    runtime = FakeRuntime("busy" if busy_at == "runtime" else "ok")
    manager = _install(monkeypatch, runtime, busy=busy_at == "claim")
    commit = Mock(wraps=db_session.commit)
    monkeypatch.setattr(db_session, "commit", commit)

    frames = list(service_module.DshAgentService(db_session).stream_turn(_dto(seeded), "tok"))

    assert [name for name, _ in frames] == ["error"]
    assert frames[-1][1]["message"] == AgentErrorCode.TURN_BUSY.message
    assert _messages(db_session, seeded) == []
    commit.assert_not_called()
    assert manager.drops == []
    if busy_at == "claim":
        assert runtime.calls == []
        assert manager.obtains == []
        assert manager.releases == []
    else:
        assert manager.releases == [(seeded["conversation_id"], manager.owner)]


@pytest.mark.parametrize("behavior", ["transport", "empty", "error", "incomplete"])
def test_nonbusy_failure_closes_runtime_without_writing_messages(db_session, seeded, monkeypatch, behavior):
    manager = _install(monkeypatch, FakeRuntime(behavior))
    commit = Mock(wraps=db_session.commit)
    monkeypatch.setattr(db_session, "commit", commit)

    frames = list(service_module.DshAgentService(db_session).stream_turn(_dto(seeded), "tok"))

    assert frames[-1][0] == "error"
    expected = {
        "transport": AgentErrorCode.RUNTIME_INTERRUPTED.message,
        "empty": AgentErrorCode.EMPTY_RESPONSE.message,
        "error": AgentErrorCode.AGENT_CALL_FAILED.message,
        "incomplete": AgentErrorCode.EMPTY_RESPONSE.message,
    }[behavior]
    assert frames[-1][1]["message"] == expected
    assert "private downstream detail" not in frames[-1][1]["message"]
    assert manager.drops == [(seeded["conversation_id"], False)]
    assert manager.releases == [(seeded["conversation_id"], manager.owner)]
    assert _messages(db_session, seeded) == []
    commit.assert_not_called()


def test_upstream_failure_detail_is_logged(db_session, seeded, monkeypatch, caplog):
    """模型侧失败必须把上游原文写进日志：否则「模型未返回有效内容」无从排查。"""

    _install(monkeypatch, FakeRuntime("upstream_error"))
    with caplog.at_level(logging.ERROR, logger=service_module.__name__):
        frames = list(service_module.DshAgentService(db_session).stream_turn(_dto(seeded), "tok"))

    assert frames[-1][0] == "error"
    assert frames[-1][1]["message"] == AgentErrorCode.EMPTY_RESPONSE.message
    logged = next(record.getMessage() for record in caplog.records if "无有效完成结果" in record.getMessage())
    assert "upstream=" in logged
    assert "model_not_found" in logged
    assert "finish_reason=error" in logged


@pytest.mark.parametrize("events", [
    None, [], "not-a-list", [None], [{"data": None}], [{"data": {"chunk": "x"}}],
    [{"data": {"reason": {"kind": "error"}}}], [{"data": {"reason": {"error": {}}}}],
])
def test_upstream_failure_detail_tolerates_odd_events(events):
    """事件缺失/畸形时返回 None，不能在错误路径上再抛异常。"""

    assert service_module._upstream_failure_detail(events) is None


def test_upstream_failure_detail_truncates_and_prefers_last():
    long_message = "x" * 900
    events = [
        {"data": {"reason": {"error": {"message": "早期错误"}}}},
        {"data": {"chunk": {"finish": {"reason": {"failure": {"message": long_message}}}}}},
    ]

    detail = service_module._upstream_failure_detail(events, limit=100)

    assert detail is not None
    assert len(detail) == 101 and detail.endswith("…")
    assert detail.startswith("x" * 10)


@pytest.mark.parametrize("behavior, exception_type", [("transport", TransportClosedError), ("error", RuntimeError)])
def test_cross_thread_failure_logs_original_exc_info(db_session, seeded, monkeypatch, caplog, behavior, exception_type):
    _install(monkeypatch, FakeRuntime(behavior))
    with caplog.at_level(logging.ERROR, logger=service_module.__name__):
        list(service_module.DshAgentService(db_session).stream_turn(_dto(seeded), "tok"))

    failure = next(record for record in caplog.records if "轮次失败" in record.getMessage())
    assert failure.exc_info is not None
    assert failure.exc_info[0] is exception_type
    assert isinstance(failure.exc_info[1], exception_type)
    assert "run_turn" in [frame.name for frame in traceback.extract_tb(failure.exc_info[2])]
    assert str(seeded["conversation_id"]) in failure.getMessage()
    assert "test-model" in failure.getMessage()


@pytest.mark.parametrize("behavior", ["blocking", "ok"])
def test_generator_close_cleans_even_when_worker_finished_before_terminal_consumed(db_session, seeded, monkeypatch, behavior):
    runtime = FakeRuntime(behavior)
    manager = _install(monkeypatch, runtime)
    turn = service_module.DshAgentService(db_session).stream_turn(_dto(seeded), "tok")
    try:
        assert next(turn)[0] == "session.event"
        if behavior == "ok":
            assert runtime.finished.wait(1)
    finally:
        turn.close()

    assert runtime.cancel_event.is_set()
    assert runtime.finished.wait(1)
    assert manager.drops == [(seeded["conversation_id"], False)]
    assert manager.releases == [(seeded["conversation_id"], manager.owner)]
    assert _messages(db_session, seeded) == []
    assert not any(t.name == f"dsh-turn-{seeded['conversation_id']}" for t in threading.enumerate())


def test_external_cancel_event_is_forwarded_and_cleans_turn(db_session, seeded, monkeypatch):
    runtime = FakeRuntime("blocking")
    manager = _install(monkeypatch, runtime)
    cancel_event = threading.Event()
    turn = service_module.DshAgentService(db_session).stream_turn(_dto(seeded), "tok", cancel_event)
    try:
        assert next(turn)[0] == "session.event"
        cancel_event.set()
        assert list(turn) == []
    finally:
        turn.close()
    assert runtime.cancel_event is cancel_event
    assert runtime.finished.wait(1)
    assert manager.releases == [(seeded["conversation_id"], manager.owner)]
    assert manager.drops == [(seeded["conversation_id"], False)]
    assert _messages(db_session, seeded) == []


def test_stalled_turn_times_out_and_releases_owner(db_session, seeded, monkeypatch):
    runtime = FakeRuntime("silent")
    runtime.turn_timeout_seconds = 0.05
    manager = _install(monkeypatch, runtime)
    frames = list(service_module.DshAgentService(db_session).stream_turn(_dto(seeded), "tok"))
    assert frames[-1][0] == "error"
    assert frames[-1][1]["message"] == AgentErrorCode.TURN_TIMEOUT.message
    assert runtime.cancel_event.is_set()
    assert runtime.finished.wait(1)
    assert manager.drops and all(drop == (seeded["conversation_id"], False) for drop in manager.drops)
    assert manager.releases == [(seeded["conversation_id"], manager.owner)]
    assert _messages(db_session, seeded) == []


def test_bounded_queue_unblocks_flooding_worker_on_close(db_session, seeded, monkeypatch):
    queues = []
    blocked = threading.Event()
    real_queue = queue.Queue

    class RecordingQueue(real_queue):
        def put(self, item, *args, **kwargs):
            if self.full():
                blocked.set()
            return super().put(item, *args, **kwargs)

    def make_queue(*args, **kwargs):
        result = RecordingQueue(*args, **kwargs)
        queues.append(result)
        return result

    class FloodRuntime(FakeRuntime):
        def run_turn(self, question, token, on_event, *, cancel_event):
            self.cancel_event = cancel_event
            try:
                for _ in range(10000):
                    if cancel_event.is_set():
                        break
                    on_event({"type": "assistant/chunk", "data": {"text": "x"}})
                return SimpleNamespace(final_response="answer", finish_reason="completed")
            finally:
                self.finished.set()

    runtime = FloodRuntime()
    manager = _install(monkeypatch, runtime)
    monkeypatch.setattr(service_module.queue, "Queue", make_queue)
    turn = service_module.DshAgentService(db_session).stream_turn(_dto(seeded), "tok")
    try:
        assert next(turn)[0] == "session.event"
        assert blocked.wait(1)
        assert queues[0].maxsize == 128
        assert queues[0].qsize() <= 128
    finally:
        turn.close()
    assert runtime.finished.wait(1)
    assert manager.releases == [(seeded["conversation_id"], manager.owner)]
    assert _messages(db_session, seeded) == []


def test_obtain_failure_releases_claim_without_messages(db_session, seeded, monkeypatch):
    manager = _install(monkeypatch, FakeRuntime())
    monkeypatch.setattr(manager, "obtain", Mock(side_effect=RuntimeError("factory failed")))
    with pytest.raises(RuntimeError, match="factory failed"):
        list(service_module.DshAgentService(db_session).stream_turn(_dto(seeded), "tok"))
    assert manager.releases == [(seeded["conversation_id"], manager.owner)]
    assert _messages(db_session, seeded) == []


def test_persistence_failure_rolls_back_and_drops_runtime(db_session, seeded, monkeypatch):
    manager = _install(monkeypatch, FakeRuntime())
    service = service_module.DshAgentService(db_session)
    commit = Mock(side_effect=RuntimeError("commit failed"))
    rollback = Mock(wraps=db_session.rollback)
    monkeypatch.setattr(db_session, "commit", commit)
    monkeypatch.setattr(db_session, "rollback", rollback)

    with pytest.raises(RuntimeError, match="commit failed"):
        list(service.stream_turn(_dto(seeded), "tok"))

    commit.assert_called_once_with()
    rollback.assert_called_once_with()
    assert manager.drops == [(seeded["conversation_id"], False)]
    assert manager.releases == [(seeded["conversation_id"], manager.owner)]
    assert _messages(db_session, seeded) == []


def test_persistence_insert_failure_rolls_back_message_pair(db_session, seeded, monkeypatch):
    _install(monkeypatch, FakeRuntime())
    service = service_module.DshAgentService(db_session)
    persist = service._persist_message

    def fail_assistant(dto, role, content):
        if role == "ASSISTANT":
            raise RuntimeError("insert failed")
        persist(dto, role, content)

    monkeypatch.setattr(service, "_persist_message", fail_assistant)
    with pytest.raises(RuntimeError, match="insert failed"):
        list(service.stream_turn(_dto(seeded), "tok"))
    assert _messages(db_session, seeded) == []


def test_invalid_model_name_rejected(db_session, seeded):
    with pytest.raises(ValueError):
        list(service_module.DshAgentService(db_session).stream_turn(_dto(seeded, model="other-model"), "tok"))


def test_foreign_conversation_rejected(db_session, seeded):
    dto = _dto(seeded)
    dto.user_id = 999
    with pytest.raises(LookupError):
        list(service_module.DshAgentService(db_session).stream_turn(dto, "tok"))


def test_ai_config_resolved_and_decrypted(db_session, seeded):
    config = service_module.DshAgentService(db_session)._resolve_ai_config(_dto(seeded))
    assert isinstance(config, AiRuntimeConfig)
    assert config.model_name == "test-model"
    assert config.api_key == "sk-test"
    assert config.ai_url == "http://model.local/v1"
