"""RuntimeManager：创建、指纹重建与丢弃。"""

from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from dsh_bridge.manager import RuntimeManager
from dsh_bridge.runtime import AiRuntimeConfig, RuntimeBusyError
from dsh_bridge.settings import DshSettings


def _settings(tmp_paths):
    return DshSettings(
        node_bin="node",
        runtime_entry=tmp_paths["sessions"] / "unused.js",
        runtime_cwd=tmp_paths["sessions"],
        cordis_config=tmp_paths["sessions"] / "unused.yml",
        session_root_base=tmp_paths["sessions"],
        token_dir=tmp_paths["tokens"],
        idle_timeout_seconds=0.05,
        default_context_window=65536,
        request_timeout_seconds=None,
        max_tokens=None,
        system_prompt="test",
        gateway_url="http://127.0.0.1:1",
        provider="codewise",
    )


def _config(model="m1", key="sk-1", url="http://u1"):
    return AiRuntimeConfig(user_config_id=1, model_name=model, ai_url=url, api_key=key)


class RecordingRuntime:
    def __init__(self, conversation_id, ai_config):
        import time

        self.conversation_id = conversation_id
        self.ai_config = ai_config
        self.closed = False
        self.invalidated = False
        # 与 DshConversationRuntime 相同的空闲回收接口。
        self.last_used = time.monotonic()

    def matches(self, ai_config: AiRuntimeConfig) -> bool:
        return self.ai_config.fingerprint() == ai_config.fingerprint()

    def close(self):
        self.closed = True

    def invalidate(self):
        self.invalidated = True


def _manager(tmp_paths, reap_interval=30.0):
    created = []

    def factory(conversation_id, ai_config):
        runtime = RecordingRuntime(conversation_id, ai_config)
        created.append(runtime)
        return runtime

    manager = RuntimeManager(_settings(tmp_paths), reap_interval_seconds=reap_interval)
    return manager, factory, created


def test_obtain_reuses_same_runtime(tmp_paths):
    manager, factory, created = _manager(tmp_paths)
    try:
        a = manager.obtain(1, _config(), factory)
        b = manager.obtain(1, _config(), factory)
        assert a is b
        assert len(created) == 1
    finally:
        manager.shutdown_all()


def test_fingerprint_change_rebuilds_runtime(tmp_paths):
    manager, factory, created = _manager(tmp_paths)
    try:
        first = manager.obtain(1, _config(), factory)
        second = manager.obtain(1, _config(model="m2"), factory)
        assert first is not second
        assert first.closed is True  # 旧运行时被关闭
        assert len(created) == 2
    finally:
        manager.shutdown_all()


def test_drop_invalidate_only_keeps_token_cleanup(tmp_paths):
    manager, factory, created = _manager(tmp_paths)
    try:
        runtime = manager.obtain(3, _config(), factory)
        manager.drop(3, invalidate_only=True)
        assert runtime.invalidated is True
        assert runtime.closed is False
    finally:
        manager.shutdown_all()


def test_idle_reaper_closes_runtime(tmp_paths):
    manager, factory, created = _manager(tmp_paths)
    try:
        runtime = manager.obtain(4, _config(), factory)
        runtime.last_used = 0
        manager._reap_once()
        assert runtime.closed
        assert manager.obtain(4, _config(), factory) is not runtime
        assert len(created) == 2
    finally:
        manager.shutdown_all()


def test_busy_owner_cannot_be_released_by_failed_claim_or_stale_owner(tmp_paths):
    manager, factory, _ = _manager(tmp_paths)
    try:
        runtime = manager.obtain(1, _config(), factory)
        owner = manager.claim_turn(1)
        with pytest.raises(RuntimeBusyError):
            manager.claim_turn(1)
        manager.release_turn(1, object())
        with pytest.raises(RuntimeBusyError):
            manager.claim_turn(1)
        assert not runtime.closed
        manager.release_turn(1, owner)
        successor = manager.claim_turn(1)
        assert successor is not owner
        manager.release_turn(1, owner)
        with pytest.raises(RuntimeBusyError):
            manager.claim_turn(1)
        manager.release_turn(1, successor)
    finally:
        manager.shutdown_all()


def test_reaper_excludes_active_turn_until_actual_owner_releases(tmp_paths):
    manager, factory, _ = _manager(tmp_paths)
    try:
        active = manager.obtain(1, _config(), factory)
        idle = manager.obtain(2, _config(), factory)
        active.last_used = idle.last_used = 0
        owner = manager.claim_turn(1)
        manager._reap_once()
        assert idle.closed
        assert not active.closed
        assert manager.obtain(1, _config(), factory) is active
        manager.release_turn(1, object())
        manager._reap_once()
        assert not active.closed
        manager.release_turn(1, owner)
        manager._reap_once()
        assert active.closed
    finally:
        manager.shutdown_all()


def test_drop_closes_runtime_but_preserves_active_owner(tmp_paths):
    manager, factory, _ = _manager(tmp_paths)
    try:
        runtime = manager.obtain(1, _config(), factory)
        owner = manager.claim_turn(1)
        manager.drop(1, invalidate_only=False)
        assert runtime.closed
        assert not runtime.invalidated
        with pytest.raises(RuntimeBusyError):
            manager.claim_turn(1)
        manager.release_turn(1, owner)
        assert manager.claim_turn(1) is not owner
    finally:
        manager.shutdown_all()




def test_reaper_close_window_rejects_new_claim_until_close_finishes(tmp_paths):
    """回收已摘除但尚未 close 完成时，不能启动同会话新轮次。"""
    manager, factory, _ = _manager(tmp_paths)
    close_started = threading.Event()
    allow_close = threading.Event()
    try:
        runtime = manager.obtain(9, _config(), factory)
        runtime.last_used = 0

        def blocked_close():
            close_started.set()
            assert allow_close.wait(2)
            runtime.closed = True

        runtime.close = blocked_close
        reaper = threading.Thread(target=manager._reap_once, daemon=True)
        reaper.start()
        assert close_started.wait(1)
        owner = None
        try:
            with pytest.raises(RuntimeBusyError):
                owner = manager.claim_turn(9)
        finally:
            allow_close.set()
            reaper.join(2)
            if owner is not None:
                manager.release_turn(9, owner)
        assert not reaper.is_alive()
        assert runtime.closed
    finally:
        allow_close.set()
        manager.shutdown_all()


def test_concurrent_claim_spike_has_exactly_one_owner(tmp_paths):
    manager, _, _ = _manager(tmp_paths)
    barrier = threading.Barrier(16)

    def claim(_):
        barrier.wait(timeout=3)
        try:
            return manager.claim_turn(1)
        except RuntimeBusyError:
            return None

    try:
        with ThreadPoolExecutor(max_workers=16) as executor:
            for _ in range(20):
                owners = [owner for owner in executor.map(claim, range(16)) if owner is not None]
                assert len(owners) == 1
                manager.release_turn(1, owners[0])
    finally:
        manager.shutdown_all()
