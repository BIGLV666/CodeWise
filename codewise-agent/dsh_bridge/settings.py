"""dsh_bridge 的环境配置。

所有配置项都来自环境变量（.env），在进程内只读解析一次。路径默认相对
CodeWise-Agent 仓库根目录，便于本地与部署环境各自覆盖。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

_REPO_ROOT = Path(__file__).resolve().parents[1]

# persona（系统提示词）。dsh agent-spine 通过 DSH_SYSTEM_PROMPT 读取。
SYSTEM_PROMPT = (
    "你是 CodeWise 编程学习平台的智能助手。"
    "回答时优先结合当前对话和工具返回的真实数据，不要编造题目、提交记录或用户信息。"
    "当信息不足时，应明确说明缺少什么，并在存在合适工具时先调用工具。"
    "解释代码问题时先指出根因，再给出修改方向；除非用户明确要求，不要直接替用户完成整道题。"
    "回答使用简洁、清晰的中文。"
)


def _env_path(name: str, default: Path) -> Path:
    """读取路径类环境变量，未配置时回退到仓库内默认值。"""

    value = os.getenv(name)
    return Path(value) if value else default


@dataclass(frozen=True)
class DshSettings:
    """dsh 运行时的启动与资源布局配置。

    Attributes:
        node_bin: Node 可执行文件（Windows 载体要求 Node >= 22.19）。
        runtime_entry: dsh JSON-RPC 运行时入口脚本（packaged-bin.js）。
        cordis_config: 运行时加载的 cordis.yml 组合配置路径。
        session_root_base: 每个会话的 JSONL 日志根目录，实际目录为
            ``<base>/<conversation_id>``。
        token_dir: 会话私有 Bearer token 文件目录。
        idle_timeout_seconds: 运行时子进程空闲多久后被回收。
        default_context_window: 注入给 llm 适配器的默认上下文窗口（token 数），
        影响 dsh compaction 的触发时机；必须不大于用户模型的真实窗口。
        request_timeout_seconds: SDK 对运行时单次请求的超时，None 表示不限
            （一轮对话可能持续数分钟，进度通过通知流实时推送）。
    """

    node_bin: str
    runtime_entry: Path
    runtime_cwd: Path
    cordis_config: Path
    session_root_base: Path
    token_dir: Path
    idle_timeout_seconds: float
    default_context_window: int
    request_timeout_seconds: float | None
    max_tokens: int | None
    system_prompt: str
    gateway_url: str
    # LLM 供应商路由：默认走 llm-pi-ai 的 codewise 路由（纯净 OpenAI 协议）；
    # 改为 deepseek-official 可切换到官方 DeepSeek 适配器（thinking/缓存计费）。
    provider: str
    # web 抓取：可选搜索 provider key（仅经子进程 env 注入，不落盘、不打日志）。
    exa_api_key: str | None = None
    perplexity_api_key: str | None = None
    # 两个搜索 key 都在时按优先级路由（优先 exa）；仅一个时与其一致；均无时为 None。
    web_search_provider: str | None = None


def load_settings() -> DshSettings:
    """从环境变量加载设置；提供仓库内默认值，便于开箱即用。"""

    max_tokens = os.getenv("AGENT_MAX_TOKENS")
    request_timeout = os.getenv("DSH_REQUEST_TIMEOUT_SECONDS")
    exa_key = os.getenv("EXA_API_KEY") or None
    perplexity_key = os.getenv("PERPLEXITY_API_KEY") or None
    # 优先 exa：两个 key 都配置时显式路由，避免 ctx.web 的“多个可用 provider”歧义。
    search_provider = "exa" if exa_key else ("perplexity" if perplexity_key else None)
    runtime_entry = _env_path(
        "DSH_RUNTIME_ENTRY",
        _REPO_ROOT
        / "agent-runtime"
        / "node_modules"
        / "@deepseek-ai"
        / "dsh-sdk-jsonrpc-demo"
        / "lib"
        / "packaged-bin.js",
    )
    # 裸插件名（codewise-tools）从部署闭包的 node_modules 解析，
    # 因此运行时子进程的工作目录必须是 agent-runtime/。
    default_runtime_cwd = runtime_entry.parents[4] if len(runtime_entry.parents) >= 5 else _REPO_ROOT
    return DshSettings(
        node_bin=os.getenv("DSH_NODE_BIN", "node"),
        runtime_entry=runtime_entry,
        runtime_cwd=_env_path("DSH_RUNTIME_CWD", default_runtime_cwd),
        cordis_config=_env_path("DSH_CORDIS_CONFIG", _REPO_ROOT / "agent-runtime" / "cordis.yml"),
        session_root_base=_env_path("DSH_SESSION_ROOT_BASE", _REPO_ROOT / "data" / "dsh-sessions"),
        token_dir=_env_path("DSH_TOKEN_DIR", _REPO_ROOT / "data" / "tokens"),
        idle_timeout_seconds=float(os.getenv("DSH_IDLE_TIMEOUT_SECONDS", "600")),
        default_context_window=int(os.getenv("DSH_DEFAULT_CONTEXT_WINDOW", "65536")),
        request_timeout_seconds=float(request_timeout) if request_timeout else None,
        max_tokens=int(max_tokens) if max_tokens else None,
        system_prompt=SYSTEM_PROMPT,
        gateway_url=os.environ["CODEWISE_GATEWAY_URL"].rstrip("/"),
        provider=os.getenv("DSH_PROVIDER", "codewise"),
        exa_api_key=exa_key,
        perplexity_api_key=perplexity_key,
        web_search_provider=search_provider,
    )
