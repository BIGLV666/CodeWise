"""web 抓取相关配置的单元测试：provider 路由计算 + 运行时 env 透传。"""

from __future__ import annotations

from pathlib import Path

from dsh_bridge.settings import DshSettings, load_settings


def _fake(prefix: str) -> str:
    """构造明显非真实的测试值，避免在源码里出现密钥样式字面量。"""

    return prefix + "-" + "test-value"


def _settings(**kwargs) -> DshSettings:
    base = dict(
        node_bin="node",
        runtime_entry=Path("/runtime.js"),
        runtime_cwd=Path("/runtime"),
        cordis_config=Path("/cordis.yml"),
        session_root_base=Path("/sessions"),
        token_dir=Path("/tokens"),
        idle_timeout_seconds=600.0,
        default_context_window=65536,
        request_timeout_seconds=None,
        max_tokens=None,
        system_prompt="prompt",
        gateway_url="http://gateway",
        provider="codewise",
    )
    base.update(kwargs)
    return DshSettings(**base)


def test_load_settings_web_provider_routing(monkeypatch) -> None:
    monkeypatch.delenv("EXA_API_KEY", raising=False)
    monkeypatch.delenv("PERPLEXITY_API_KEY", raising=False)

    settings = load_settings()
    assert settings.exa_api_key is None
    assert settings.perplexity_api_key is None
    assert settings.web_search_provider is None

    monkeypatch.setenv("EXA_API_KEY", _fake("exa"))
    settings = load_settings()
    assert settings.exa_api_key == _fake("exa")
    assert settings.web_search_provider == "exa"

    # 两个 key 都在时优先 exa，避免 ctx.web 的“多个可用 provider”歧义。
    monkeypatch.setenv("PERPLEXITY_API_KEY", _fake("perplexity"))
    settings = load_settings()
    assert settings.web_search_provider == "exa"

    monkeypatch.delenv("EXA_API_KEY")
    settings = load_settings()
    assert settings.web_search_provider == "perplexity"


def test_build_runtime_env_injects_search_keys_only_when_set(tmp_path) -> None:
    from dsh_bridge.runtime import build_runtime_env

    settings = _settings()
    env = build_runtime_env(settings, "model-x", str(tmp_path / "token"))
    assert "EXA_API_KEY" not in env
    assert "PERPLEXITY_API_KEY" not in env
    assert "DSH_WEB_SEARCH_PROVIDER" not in env
    assert env["DSH_MODEL_ID"] == "model-x"
    assert env["CODEWISE_TOKEN_FILE"] == str(tmp_path / "token")

    settings = _settings(exa_api_key=_fake("exa"), web_search_provider="exa")
    env = build_runtime_env(settings, "model-x", "tok")
    assert env["EXA_API_KEY"] == _fake("exa")
    assert env["DSH_WEB_SEARCH_PROVIDER"] == "exa"
    assert "PERPLEXITY_API_KEY" not in env

    settings = _settings(perplexity_api_key=_fake("perplexity"), web_search_provider="perplexity")
    env = build_runtime_env(settings, "model-x", "tok")
    assert env["PERPLEXITY_API_KEY"] == _fake("perplexity")
    assert env["DSH_WEB_SEARCH_PROVIDER"] == "perplexity"
    assert "EXA_API_KEY" not in env
