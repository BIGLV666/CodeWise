"""TokenStore：原子写入、按会话隔离、清理。"""

from __future__ import annotations

from dsh_bridge.settings import DshSettings
from dsh_bridge.token_store import TokenStore


def _settings(tmp_paths) -> DshSettings:
    return DshSettings(
        node_bin="node",
        runtime_entry=tmp_paths["sessions"] / "unused.js",
        runtime_cwd=tmp_paths["sessions"],
        cordis_config=tmp_paths["sessions"] / "unused.yml",
        session_root_base=tmp_paths["sessions"],
        token_dir=tmp_paths["tokens"],
        idle_timeout_seconds=600,
        default_context_window=65536,
        request_timeout_seconds=None,
        max_tokens=None,
        system_prompt="test",
        gateway_url="http://127.0.0.1:1",
        provider="codewise",
    )


def test_write_creates_readable_token_file(tmp_paths):
    store = TokenStore(_settings(tmp_paths))
    path = store.write(42, "token-abc")
    assert path.exists()
    assert path.read_text(encoding="utf-8") == "token-abc"
    assert path.parent == tmp_paths["tokens"].resolve() or path.parent == tmp_paths["tokens"]


def test_write_is_atomic_replace(tmp_paths):
    """两次写入后文件内容必须是最新值（os.replace 原子语义）。"""

    store = TokenStore(_settings(tmp_paths))
    store.write(7, "old")
    path = store.write(7, "new")
    assert path.read_text(encoding="utf-8") == "new"
    # 临时文件不应残留
    assert not path.with_suffix(".token.tmp").exists()


def test_paths_are_isolated_per_conversation(tmp_paths):
    store = TokenStore(_settings(tmp_paths))
    store.write(1, "a")
    store.write(2, "b")
    assert store.token_path(1).read_text(encoding="utf-8") == "a"
    assert store.token_path(2).read_text(encoding="utf-8") == "b"


def test_cleanup_removes_file(tmp_paths):
    store = TokenStore(_settings(tmp_paths))
    store.write(9, "x")
    store.cleanup(9)
    assert not store.token_path(9).exists()
    # 幂等
    store.cleanup(9)


def test_write_rejects_empty_token(tmp_paths):
    store = TokenStore(_settings(tmp_paths))
    try:
        store.write(5, "")
    except ValueError:
        return
    raise AssertionError("空 token 应该被拒绝")
