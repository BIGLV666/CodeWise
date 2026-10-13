"""dsh 会话 id 的配置敏感性。

背景：dsh 恢复已存会话日志时要求活会话的 seed 覆盖日志前缀，而 seed 由运行时注入的
provider / 模型 / maxTokens / system prompt 决定。若会话 id 只含 conversation_id，
用户在同一会话里切换模型后新运行时会去 adopt 旧模型的日志、seed 不匹配，dsh 抛
``... already has a persisted log on disk that does not match this live session
(id collision)``，该会话从此每轮都失败（表现为「模型未返回有效内容」）。
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


def test_same_config_yields_same_session_id():
    """同配置必须稳定：进程重启后要按同一 id 恢复 JSONL 上下文。"""

    settings = _settings()

    assert build_session_id(7, settings, _ai_config()) == build_session_id(7, settings, _ai_config())


def test_model_switch_changes_session_id():
    """核心回归：换模型必须换 id，否则 dsh 会 adopt 旧模型日志并抛 id collision。"""

    settings = _settings()

    assert build_session_id(7, settings, _ai_config(model_name="kimi-k3")) != \
        build_session_id(7, settings, _ai_config(model_name="deepseek-v4.1-flash"))


def test_provider_system_prompt_and_endpoint_change_session_id():
    ai = _ai_config()

    assert build_session_id(7, _settings(), ai) != \
        build_session_id(7, _settings(provider="deepseek-official"), ai)
    assert build_session_id(7, _settings(), ai) != \
        build_session_id(7, _settings(system_prompt="另一个提示词"), ai)
    assert build_session_id(7, _settings(), ai) != \
        build_session_id(7, _settings(), _ai_config(ai_url="https://other.example/v1"))


def test_session_id_is_prefixed_and_path_safe():
    """会话号保留在前缀，便于运维按会话定位日志；整串不能含路径分隔符或空格。"""

    sid = build_session_id(12, _settings(), _ai_config())

    assert sid.startswith("cw-12-")
    assert "/" not in sid and "\\" not in sid and " " not in sid
    assert sid != build_session_id(13, _settings(), _ai_config())
