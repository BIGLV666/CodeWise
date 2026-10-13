"""dsh 会话 id 的构造契约。

背景：dsh 恢复已存会话日志用的是 ``seedCoversPrefix``——要求已存前缀与活会话 seed
**逐字节 JSON 相等**，而 seed 含创建时间戳等易变字段，新进程无法复现，必然抛
``... does not match this live session (id collision)``（实测：同一会话号连跑两次，
第二次必失败）。dsh 注释也说明该 adopt 路径只用于同进程 HMR/reload。

因此会话 id 必须带运行实例后缀：永不碰撞，同一实例内多轮共用同一会话保持上下文连续。
"""

from __future__ import annotations

from pathlib import Path

from dsh_bridge.runtime import AiRuntimeConfig, build_session_id
from dsh_bridge.settings import DshSettings


def _settings(**overrides) -> DshSettings:
    base = dict(
        node_bin="node", runtime_entry=Path("/tmp/unused.js"), runtime_cwd=Path("/tmp"),
        cordis_config=Path("/tmp/unused.yml"), session_root_base=Path("/tmp/sessions"),
        token_dir=Path("/tmp/tokens"), idle_timeout_seconds=1, default_context_window=65536,
        request_timeout_seconds=None, max_tokens=None, system_prompt="test",
        gateway_url="http://127.0.0.1:1", provider="codewise",
    )
    base.update(overrides)
    return DshSettings(**base)


def _ai_config(**overrides) -> AiRuntimeConfig:
    base = dict(user_config_id=2, model_name="deepseek-v4.1-flash",
                ai_url="https://relay.example/v1", api_key="sk-test", max_tokens=None)
    base.update(overrides)
    return AiRuntimeConfig(**base)


def test_two_runtime_instances_never_share_session_id():
    """核心回归：不同运行时实例必须拿到不同 id，否则第二个实例会去 adopt 旧日志并被 dsh 判 id collision。"""

    settings = _settings()

    first = build_session_id(7, settings, _ai_config(), "aaaaaaaa")
    second = build_session_id(7, settings, _ai_config(), "bbbbbbbb")

    assert first != second


def test_same_instance_keeps_one_session_id():
    """同一实例内的多轮必须固定用同一 id，否则每轮都开新会话、上下文不连续。"""

    settings = _settings()
    ai = _ai_config()

    assert build_session_id(7, settings, ai, "abc12345") == build_session_id(7, settings, ai, "abc12345")


def test_session_id_carries_conversation_and_config_digest():
    """id 自带会话号与配置摘要：日志目录自解释，换配置在目录名上可见。"""

    settings = _settings()
    same_config_other_model = build_session_id(7, settings, _ai_config(model_name="kimi-k3"), "abc12345")
    other_conversation = build_session_id(8, settings, _ai_config(), "abc12345")

    assert same_config_other_model.startswith("cw-7-")
    assert same_config_other_model.split("-")[2] != build_session_id(7, settings, _ai_config(), "abc12345").split("-")[2]
    assert other_conversation.startswith("cw-8-")


def test_session_id_is_path_safe():
    """id 会变成日志目录名，不能含路径分隔符或空格。"""

    sid = build_session_id(12, _settings(), _ai_config(), "00ff00ff")

    assert "/" not in sid and "\\" not in sid and " " not in sid
