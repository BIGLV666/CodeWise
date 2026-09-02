"""RuntimeManager：创建、指纹重建与丢弃。"""

from __future__ import annotations

from dsh_bridge.manager import RuntimeManager
from dsh_bridge.runtime import AiRuntimeConfig
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
    """空闲超时（0.05s）后，扫描循环应关闭并移除运行时。"""

    import time

    manager, factory, created = _manager(tmp_paths, reap_interval=0.05)
    try:
        manager.obtain(4, _config(), factory)
        time.sleep(0.3)
        assert len(created) == 1
        assert created[0].closed is True
    finally:
        manager.shutdown_all()
