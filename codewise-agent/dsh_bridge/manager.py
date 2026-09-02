"""会话运行时注册表：懒启动、空闲回收、指纹比对重建。"""

from __future__ import annotations

import atexit
import logging
import threading
import time
from typing import Callable

from dsh_bridge.runtime import AiRuntimeConfig, DshConversationRuntime
from dsh_bridge.settings import DshSettings
from dsh_bridge.token_store import TokenStore

logger = logging.getLogger(__name__)


class RuntimeManager:
    """管理所有会话的 dsh 运行时子进程。

    - ``obtain``：按会话取运行时；AI 配置指纹变化时关闭旧进程并重建；
    - 空闲回收：后台守护线程定期关闭超过 ``idle_timeout_seconds`` 未使用的
      运行时（JSONL 会话日志保证下次唤起时无损续接）；
    - ``shutdown_all``：进程退出时统一回收（atexit 注册）。
    """

    def __init__(self, settings: DshSettings, reap_interval_seconds: float = 30.0) -> None:
        self._settings = settings
        self._reap_interval = reap_interval_seconds
        self._token_store = TokenStore(settings)
        self._runtimes: dict[int, DshConversationRuntime] = {}
        self._lock = threading.Lock()
        self._closed = False
        self._reaper = threading.Thread(target=self._reap_loop, name="dsh-runtime-reaper", daemon=True)
        self._reaper.start()
        atexit.register(self.shutdown_all)

    def obtain(
        self,
        conversation_id: int,
        ai_config: AiRuntimeConfig,
        factory: Callable[[int, AiRuntimeConfig], DshConversationRuntime] | None = None,
    ) -> DshConversationRuntime:
        """获取（或创建）指定会话的运行时。

        Args:
            conversation_id: agent 会话主键。
            ai_config: 当前请求解析出的模型配置；指纹与现有运行时不一致时重建。
            factory: 运行时构造器（测试可注入替身）。
        """

        make = factory or self._default_factory
        with self._lock:
            if self._closed:
                raise RuntimeError("RuntimeManager 已关闭")
            runtime = self._runtimes.get(conversation_id)
            if runtime is not None and not runtime.matches(ai_config):
                # 模型/配置变更：先关旧进程；JSONL 会话由新进程按同 id 恢复。
                runtime.close()
                runtime = None
            if runtime is None:
                runtime = make(conversation_id, ai_config)
                self._runtimes[conversation_id] = runtime
            return runtime

    def drop(self, conversation_id: int, *, invalidate_only: bool = False) -> None:
        """移除指定会话的运行时。

        Args:
            invalidate_only: True 时只丢弃子进程引用（通信故障后的软重建）；
                False 时彻底关闭并清理 token 文件。
        """

        with self._lock:
            runtime = self._runtimes.pop(conversation_id, None)
        if runtime is None:
            return
        if invalidate_only:
            runtime.invalidate()
        else:
            runtime.close()

    def _default_factory(self, conversation_id: int, ai_config: AiRuntimeConfig) -> DshConversationRuntime:
        return DshConversationRuntime(conversation_id, self._settings, self._token_store, ai_config)

    def _reap_loop(self) -> None:
        """空闲回收循环；扫描间隔可配置，默认 30 秒。"""

        while True:
            time.sleep(self._reap_interval)
            try:
                self._reap_once()
            except Exception:  # noqa: BLE001 - 守护线程不允许因偶发异常退出
                logger.exception("空闲回收扫描失败")

    def _reap_once(self) -> None:
        deadline = time.monotonic() - self._settings.idle_timeout_seconds
        with self._lock:
            expired = [
                conversation_id
                for conversation_id, runtime in self._runtimes.items()
                if runtime.last_used < deadline
            ]
            runtimes = [self._runtimes.pop(conversation_id) for conversation_id in expired]
        for runtime in runtimes:
            logger.info("回收空闲 dsh 运行时 conversation_id=%s", runtime.conversation_id)
            runtime.close()

    def shutdown_all(self) -> None:
        """关闭全部运行时子进程；atexit 与测试清理共用。"""

        with self._lock:
            if self._closed:
                return
            self._closed = True
            runtimes = list(self._runtimes.values())
            self._runtimes.clear()
        for runtime in runtimes:
            runtime.close()
