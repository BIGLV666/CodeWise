"""真实 DshConversationRuntime 生命周期；仅替换 SDK harness，不启动 Node。"""

from __future__ import annotations

import threading
from types import SimpleNamespace

import pytest

from dsh_bridge import runtime as runtime_module
from dsh_bridge.runtime import AiRuntimeConfig, DshConversationRuntime, RuntimeBusyError
from dsh_bridge.settings import DshSettings
from dsh_bridge.token_store import TokenStore


@pytest.fixture()
def runtime_env(tmp_paths, monkeypatch):
    settings = DshSettings(
        node_bin="node", runtime_entry=tmp_paths["sessions"] / "unused.js",
        runtime_cwd=tmp_paths["sessions"], cordis_config=tmp_paths["sessions"] / "unused.yml",
        session_root_base=tmp_paths["sessions"], token_dir=tmp_paths["tokens"],
        idle_timeout_seconds=1, default_context_window=65536, request_timeout_seconds=None,
        max_tokens=None, system_prompt="test", gateway_url="http://127.0.0.1:1", provider="codewise",
    )
    created = []

    class Harness:
        def __init__(self, config):
            self.config = config
            self.closed = False
            self.close_calls = 0
            self.run_calls = 0
            self.prompts = []
            self.behavior = None
            created.append(self)

        def start(self):
            pass

        def run(self, question, *, session_id, on_notification):
            self.run_calls += 1
            self.prompts.append(question)
            if self.behavior:
                self.behavior(on_notification)
            return SimpleNamespace(final_response="answer", finish_reason="completed")

        def close(self):
            self.closed = True
            self.close_calls += 1

    monkeypatch.setattr(runtime_module, "DeepSeekHarness", Harness)
    store = TokenStore(settings)
    runtime = DshConversationRuntime(42, settings, store, AiRuntimeConfig(1, "model", "http://model/v1", "test-key"))
    try:
        yield runtime, store, created
    finally:
        runtime.close()


def test_success_refreshes_token_reuses_harness_and_close_cleans_file(runtime_env):
    runtime, store, created = runtime_env
    first = runtime.run_turn("hi", "token-1", lambda _: None, cancel_event=threading.Event())
    assert first.final_response == "answer"
    assert store.token_path(42).read_text() == "token-1"
    runtime.run_turn("again", "token-2", lambda _: None, cancel_event=threading.Event())
    assert len(created) == 1
    assert created[0].run_calls == 2
    assert store.token_path(42).read_text() == "token-2"
    assert created[0].config.env["CODEWISE_TOKEN_FILE"] == str(store.token_path(42))
    assert "token-2" not in created[0].config.env.values()
    runtime.close()
    runtime.close()
    assert created[0].close_calls == 1
    assert not store.token_path(42).exists()
    assert not store.token_path(42).with_suffix(".token.tmp").exists()


def test_first_turn_injects_context_replay_and_later_turns_do_not(runtime_env):
    """新实例首轮把前情提要拼进提示词；之后不再回放（dsh 会话里已有上下文）。"""

    runtime, _, created = runtime_env

    runtime.run_turn("现在的问题", "tok", lambda _: None, cancel_event=threading.Event(),
                     history="【本会话早前的对话记录…】\n用户：早前的问题")

    assert runtime.needs_context_replay is False
    runtime.run_turn("第二个问题", "tok", lambda _: None, cancel_event=threading.Event())

    prompts = created[0].prompts
    assert "早前的问题" in prompts[0] and "现在的问题" in prompts[0]
    assert prompts[1] == "第二个问题"


def test_compose_prompt_passes_bare_input_when_no_history():
    assert runtime_module.compose_prompt("hi") == "hi"
    assert runtime_module.compose_prompt("hi", history=None) == "hi"
    assert runtime_module.compose_prompt("hi", history="   ") == "hi"


def test_pre_cancelled_turn_never_starts_harness_and_removes_existing_token(runtime_env):
    runtime, store, created = runtime_env
    store.write(42, "old-token")
    cancel = threading.Event()
    cancel.set()
    with pytest.raises(RuntimeError, match="取消"):
        runtime.run_turn("hi", "new-token", lambda _: None, cancel_event=cancel)
    assert created == []
    assert not store.token_path(42).exists()
    assert not runtime._busy_lock.locked()


def test_cancel_after_harness_start_prevents_run_and_cleans_token(runtime_env, monkeypatch):
    runtime, store, created = runtime_env
    cancel = threading.Event()
    ensure = runtime._ensure_harness

    def start_then_cancel(path):
        harness = ensure(path)
        cancel.set()
        return harness

    monkeypatch.setattr(runtime, "_ensure_harness", start_then_cancel)
    with pytest.raises(RuntimeError, match="取消"):
        runtime.run_turn("hi", "new-token", lambda _: None, cancel_event=cancel)
    assert created[0].run_calls == 0
    assert created[0].closed
    assert not store.token_path(42).exists()
    assert not runtime._busy_lock.locked()


def test_cancellation_during_run_suppresses_late_events_and_cleans_token(runtime_env):
    runtime, store, created = runtime_env
    runtime.run_turn("warmup", "initial", lambda _: None)
    cancel = threading.Event()
    seen = []
    notification = SimpleNamespace(method="session.event", payload={"event": {"type": "assistant/chunk"}})

    def cancel_during_run(forward):
        forward(notification)
        cancel.set()
        forward(notification)

    created[0].behavior = cancel_during_run
    runtime.run_turn("hi", "token", seen.append, cancel_event=cancel)
    assert seen == [{"type": "assistant/chunk"}]
    assert created[0].closed
    assert not store.token_path(42).exists()
    assert not runtime._busy_lock.locked()


def test_busy_attempt_does_not_overwrite_or_delete_owner_token(runtime_env):
    runtime, store, created = runtime_env
    store.write(42, "owner-token")
    runtime._busy_lock.acquire()
    try:
        cancelled = threading.Event()
        cancelled.set()
        with pytest.raises(RuntimeBusyError):
            runtime.run_turn("intruder", "intruder-token", lambda _: None, cancel_event=cancelled)
        assert store.token_path(42).read_text() == "owner-token"
        assert created == []
        assert runtime._busy_lock.locked()
    finally:
        runtime._busy_lock.release()


def test_close_cleans_token_even_if_harness_close_raises(runtime_env):
    runtime, store, created = runtime_env
    runtime.run_turn("hi", "token", lambda _: None)

    def failed_close():
        raise RuntimeError("close failed")

    created[0].close = failed_close
    runtime.close()
    assert runtime._harness is None
    assert not store.token_path(42).exists()


def test_cancel_during_blocking_harness_start_closes_initialized_harness(runtime_env, monkeypatch):
    runtime, store, created = runtime_env
    cancel = threading.Event()
    started = threading.Event()
    allow_start = threading.Event()

    class Harness:
        def __init__(self, config):
            self.config = config
            self.closed = False
            created.append(self)

        def start(self):
            started.set()
            allow_start.wait(2)

        def close(self):
            self.closed = True
            allow_start.set()

        def run(self, *args, **kwargs):
            raise AssertionError("cancelled initialization must not run a turn")

    monkeypatch.setattr(runtime_module, "DeepSeekHarness", Harness)
    errors = []

    def run():
        try:
            runtime.run_turn("hi", "token", lambda _: None, cancel_event=cancel)
        except BaseException as exc:
            errors.append(exc)

    worker = threading.Thread(target=run)
    worker.start()
    assert started.wait(1)
    cancel.set()
    runtime.close()
    allow_start.set()
    worker.join(2)
    assert not worker.is_alive()
    assert errors and isinstance(errors[0], RuntimeError)
    assert created[-1].closed
    assert not store.token_path(42).exists()
    assert not runtime._busy_lock.locked()


def test_harness_start_failure_invalidates_harness_and_token(runtime_env, monkeypatch):
    runtime, store, created = runtime_env

    class FailingHarness:
        def __init__(self, config):
            self.closed = False
            created.append(self)

        def start(self):
            raise RuntimeError("start failed")

        def close(self):
            self.closed = True

    monkeypatch.setattr(runtime_module, "DeepSeekHarness", FailingHarness)
    with pytest.raises(RuntimeError, match="start failed"):
        runtime.run_turn("hi", "token", lambda _: None)
    assert created[-1].closed
    assert runtime._harness is None
    assert not store.token_path(42).exists()
    assert not runtime._busy_lock.locked()


def test_turn_timeout_default_and_environment_override(runtime_env, monkeypatch):
    runtime, store, _ = runtime_env
    monkeypatch.delenv("DSH_TURN_TIMEOUT_SECONDS", raising=False)
    config = AiRuntimeConfig(1, "model", "http://model/v1", "test-key")
    default = DshConversationRuntime(43, runtime._settings, store, config)
    assert default.turn_timeout_seconds == 300
    monkeypatch.setenv("DSH_TURN_TIMEOUT_SECONDS", "0.05")
    configured = DshConversationRuntime(44, runtime._settings, store, config)
    assert configured.turn_timeout_seconds == 0.05
