"""单个会话的 dsh 运行时封装。

一个 ``DshConversationRuntime`` 对应一个 agent 会话（conversation），持有：

- 一个 dsh JSON-RPC 运行时子进程（DeepSeekHarness，懒启动、跨轮复用）；
- 该会话的 JSONL 会话日志目录（dsh 的“记忆”载体，按 session_id 恢复）；
- 该会话专属的模型端点凭据与内网网关地址（进程级隔离，不跨用户共享）。

会话切换模型 / 更换 AI 配置时，通过指纹比对判定需要重建子进程；JSONL 日志
使新进程能无损续接原会话上下文。
"""

from __future__ import annotations

import hashlib
import logging
import os
import threading
import time
import uuid
from dataclasses import dataclass

from dsh_bridge.sdk import DeepSeekHarness, DeepSeekHarnessConfig, RunResult
from dsh_bridge.settings import DshSettings
from dsh_bridge.token_store import TokenStore

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class AiRuntimeConfig:
    """启动运行时所需的一组用户模型与网关凭据。

    Attributes:
        user_config_id: 用户 AI 配置主键（参与指纹）。
        model_name: 本次使用的模型 ID（透传给 OpenAI 兼容端点）。
        ai_url: OpenAI 兼容端点 base URL。
        api_key: 解密后的模型 API Key（只进子进程 env，不落盘、不打日志）。
        max_tokens: 可选的单请求输出上限。
    """

    user_config_id: int
    model_name: str
    ai_url: str
    api_key: str
    max_tokens: int | None = None

    def fingerprint(self) -> str:
        """返回用于判断“是否需要重建运行时”的指纹。

        指纹包含配置 ID、模型、端点与密钥摘要（SHA-256 前 16 位，避免明文留存）。
        """

        key_digest = hashlib.sha256(self.api_key.encode("utf-8")).hexdigest()[:16]
        return f"{self.user_config_id}|{self.model_name}|{self.ai_url}|{key_digest}|{self.max_tokens}"


class RuntimeBusyError(RuntimeError):
    """同一会话已有一轮对话在执行时抛出。"""


def build_session_id(conversation_id: int, settings: "DshSettings", ai_config: AiRuntimeConfig,
                     instance_nonce: str) -> str:
    """构造 dsh 会话 id：会话号 + 配置摘要 + **运行实例随机后缀**。

    dsh 恢复已存会话日志的判定是 ``seedCoversPrefix``：它要求**已存日志的前缀与活会话 seed
    逐字节 JSON 相等**（``JSON.stringify(seedEvent) === JSON.stringify(event)``），而 seed
    里含创建时间戳等每次都会变的字段，因此新进程**永远无法**复现旧前缀，dsh 必然抛
    ``session "cw-N" ... does not match this live session (id collision)``——该会话此后每轮
    都失败（用户看到「模型未返回有效内容」或 SSE 流空返回/中断）。dsh 自己的注释也写明该
    adopt 路径是给 **HMR/reload（同进程）** 用的。

    所以不能让同一会话在新进程里复用旧 id：

    - 实例后缀让每个运行时实例拿到自己的会话（dsh 走"全新会话"分支，永不碰撞）。同一实例
      内的多轮对话仍共用该会话，上下文连续；实例被回收（空闲超时 / 容器重启 / 模型配置变更）
      后开启新会话，模型上下文从零开始——跨实例的连续上下文应由门面用 MySQL 里的消息历史
      回放（DB 才是历史的事实来源，JSONL 只是运行期工作日志）。
    - 配置摘要（``AiRuntimeConfig.fingerprint()`` 叠加 provider 与 system prompt）只用于让日志
      目录自解释：出问题时一眼看出那是哪套配置写的。
    """

    seed_inputs = "\x1f".join([
        ai_config.fingerprint(),
        settings.provider,
        settings.system_prompt or "",
    ])
    digest = hashlib.sha256(seed_inputs.encode("utf-8")).hexdigest()[:10]
    return f"cw-{conversation_id}-{digest}-{instance_nonce}"


class DshConversationRuntime:
    """一个会话的 dsh 运行时生命周期与轮次执行。"""

    def __init__(
        self,
        conversation_id: int,
        settings: DshSettings,
        token_store: TokenStore,
        ai_config: AiRuntimeConfig,
    ) -> None:
        self.conversation_id = conversation_id
        self._settings = settings
        self._token_store = token_store
        self._ai_config = ai_config
        self._fingerprint = ai_config.fingerprint()
        self._busy_lock = threading.Lock()
        self._harness: DeepSeekHarness | None = None
        # dsh 会话 id：每个运行时实例独立（理由见 build_session_id）。同一实例内的多轮共用
        # 同一会话，上下文连续；实例被回收后开新会话，避免去 adopt 旧日志而整会话失效。
        self.session_id = build_session_id(conversation_id, settings, ai_config, uuid.uuid4().hex[:8])
        self.session_root = settings.session_root_base / str(conversation_id)
        self.last_used = time.monotonic()
        self.turn_timeout_seconds = float(os.getenv("DSH_TURN_TIMEOUT_SECONDS", "300"))

    def run_turn(
        self,
        question: str,
        token: str,
        on_event,
        *,
        cancel_event: threading.Event | None = None,
    ) -> RunResult:
        """执行一轮对话；on_event 会被实时回调，参数是 dsh session event 字典。

        Args:
            question: 用户输入文本。
            token: 该用户当前 Bearer token；写入会话私有文件供工具读取。
            on_event: 回调 ``Callable[[JsonObject], None]``，在运行时线程内同步调用。

        Raises:
            RuntimeBusyError: 上一轮尚未结束。
            dsh_bridge.sdk.HarnessError: 运行时通信失败（由上层决定是否重建）。
        """

        if not self._busy_lock.acquire(blocking=False):
            raise RuntimeBusyError("该会话上一轮对话尚未结束")
        cancel_event = cancel_event or threading.Event()
        try:
            if cancel_event.is_set():
                raise RuntimeError("Agent 轮次已取消")
            token_path = self._token_store.write(self.conversation_id, token)
            harness = self._ensure_harness(token_path)
            self.last_used = time.monotonic()
            if cancel_event.is_set():
                raise RuntimeError("Agent 轮次已取消")
            return harness.run(
                question,
                session_id=self.session_id,
                on_notification=_make_event_forwarder(on_event, cancel_event),
            )
        finally:
            if cancel_event.is_set():
                self.close()
            self._busy_lock.release()
            self.last_used = time.monotonic()

    def matches(self, ai_config: AiRuntimeConfig) -> bool:
        """判断当前运行时是否仍与给定模型配置一致。"""

        return self._fingerprint == ai_config.fingerprint()

    def close(self) -> None:
        """关闭运行时子进程并清理 token 文件。幂等。"""

        if self._harness is not None:
            try:
                self._harness.close()
            except Exception:  # noqa: BLE001 - 回收路径必须尽力而为
                logger.exception("关闭 dsh 运行时失败 conversation_id=%s", self.conversation_id)
            self._harness = None
        self._token_store.cleanup(self.conversation_id)

    def invalidate(self) -> None:
        """通信失败后丢弃子进程引用，下次运行重建（JSONL 会话自动恢复）。"""

        if self._harness is not None:
            try:
                self._harness.close()
            except Exception:  # noqa: BLE001
                pass
            self._harness = None

    def _ensure_harness(self, token_path) -> DeepSeekHarness:
        """懒启动运行时子进程，注入该会话专属凭据与配置。"""

        if self._harness is not None:
            return self._harness

        # 子进程 cwd 与 DSH_SESSION_ROOT 都指向会话目录，必须先存在。
        self.session_root.mkdir(parents=True, exist_ok=True)
        settings = self._settings
        env = build_runtime_env(settings, self._ai_config.model_name, str(token_path))
        config = DeepSeekHarnessConfig(
            provider=settings.provider,
            model=self._ai_config.model_name,
            max_tokens=self._ai_config.max_tokens or settings.max_tokens,
            cwd=str(self.session_root),
            runtime_cwd=str(settings.runtime_cwd),
            session_root=str(self.session_root),
            env=env,
            base_url=self._ai_config.ai_url,
            api_key=self._ai_config.api_key,
            request_timeout_seconds=settings.request_timeout_seconds,
            launch_args_override=(
                settings.node_bin,
                str(settings.runtime_entry),
            ),
        )
        harness = DeepSeekHarness(config)
        # 启动期也必须可取消：initialize 可能阻塞，关闭路径需要先拿到 harness。
        self._harness = harness
        try:
            harness.start()
        except BaseException:
            self.close()
            raise
        logger.info(
            "dsh 运行时已启动 conversation_id=%s session=%s model=%s",
            self.conversation_id,
            self.session_id,
            self._ai_config.model_name,
        )
        return harness


def _make_event_forwarder(on_event, cancel_event: threading.Event | None = None):
    """把 SDK 通知转换成 dsh session event 回调。

    SDK 的 Session.run 会把根会话事件同时放进通知流；这里只挑出
    ``session.event`` 通知里的 event 字典交给上层，避免内部协议细节泄漏。
    """

    def forward(notification) -> None:
        if cancel_event is not None and cancel_event.is_set():
            return
        if notification.method != "session.event":
            return
        event = notification.payload.get("event")
        if isinstance(event, dict):
            on_event(event)

    return forward


def new_session_id() -> str:
    """生成一次性 session id（当前实现使用确定性 id，此函数保留给测试）。"""

    return f"session-{uuid.uuid4().hex}"


def build_runtime_env(settings: DshSettings, model_name: str, token_path: str) -> dict[str, str]:
    """构造 dsh 运行时子进程的环境变量。

    搜索 key（EXA_API_KEY / PERPLEXITY_API_KEY）与 provider 路由
    （DSH_WEB_SEARCH_PROVIDER）只在配置后才注入；与模型 API key 同级处理——
    只进子进程 env，不落盘、不打日志。
    """

    env: dict[str, str] = {
        "DSH_CORDIS_CONFIG": str(settings.cordis_config),
        "DSH_SYSTEM_PROMPT": settings.system_prompt,
        "DSH_CONTEXT_WINDOW": str(settings.default_context_window),
        # llm-pi-ai 的模型目录按进程声明：目录 id = 本会话使用的模型 id，
        # 请求时模型 id 即透传为 wire model 字段。
        "DSH_MODEL_ID": model_name,
        "CODEWISE_GATEWAY_URL": settings.gateway_url,
        "CODEWISE_TOKEN_FILE": token_path,
    }
    if settings.exa_api_key:
        env["EXA_API_KEY"] = settings.exa_api_key
    if settings.perplexity_api_key:
        env["PERPLEXITY_API_KEY"] = settings.perplexity_api_key
    if settings.web_search_provider:
        env["DSH_WEB_SEARCH_PROVIDER"] = settings.web_search_provider
    return env
