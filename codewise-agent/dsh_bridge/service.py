"""Agent 门面服务：把一轮对话落到 dsh 运行时，并把过程桥接为 SSE 事件流。

职责：
1. 校验请求与会话归属，解析并解密用户 AI 配置；
2. 持久化 USER / ASSISTANT 消息（前端历史列表数据源，与旧版一致）；
3. 通过 RuntimeManager 取得会话运行时，执行一轮对话并实时转发 dsh 事件；
4. 终止帧（done / error）与运行时故障恢复：子进程丢弃后**用 MySQL 里的消息历史回放**
   把模型上下文接回去（dsh 的 JSONL 会话日志无法跨进程恢复，见 runtime.build_session_id）。

dsh 侧负责的：agent 循环、工具执行、JSONL 会话日志与自动压缩。
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from collections.abc import Iterator
from typing import Any

from sqlalchemy.orm import Session as DbSession

from dsh_bridge.bridge import done_payload, error_payload, sse_payload
from dsh_bridge.manager import RuntimeManager
from dsh_bridge.runtime import AiRuntimeConfig, RuntimeBusyError
from dsh_bridge.sdk.errors import TransportClosedError
from dsh_bridge.settings import load_settings
from dto.CallAiDto import CallAiDto
from entry.AgentMessage import AgentMessage
from mapper import AgentConversationMapper, AgentMessageMapper
from mapper import UserAiConfigMapper
from util import ApiKeyCrypto
from util.error_codes import AgentErrorCode

logger = logging.getLogger(__name__)

_runtime_manager: RuntimeManager | None = None
_manager_lock = threading.Lock()


def get_runtime_manager() -> RuntimeManager:
    """返回进程级 RuntimeManager 单例（测试可用 set_runtime_manager 替换）。"""

    global _runtime_manager
    with _manager_lock:
        if _runtime_manager is None:
            _runtime_manager = RuntimeManager(load_settings())
        return _runtime_manager


def set_runtime_manager(manager: RuntimeManager | None) -> None:
    """替换/清空 RuntimeManager 单例，仅供测试使用。"""

    global _runtime_manager
    with _manager_lock:
        _runtime_manager = manager


class DshAgentService:
    """面向 HTTP 层的一轮对话编排。"""

    def __init__(self, db: DbSession) -> None:
        self.db = db
        self.agent_message_mapper = AgentMessageMapper(db)
        self.agent_conversation_mapper = AgentConversationMapper(db)

    def _validate(self, call_ai_dto: CallAiDto) -> str:
        """校验请求参数与会话归属，返回清洗后的用户问题。"""

        question = call_ai_dto.question.strip()
        if not question:
            raise ValueError("问题不能为空")
        if not call_ai_dto.conversation_id:
            raise ValueError("对话 ID 不能为空")
        if not call_ai_dto.user_id:
            raise ValueError("用户 ID 不能为空")
        conversation = self.agent_conversation_mapper.get_agent_conversation(
            call_ai_dto.conversation_id,
            call_ai_dto.user_id,
        )
        if conversation is None:
            raise LookupError("对话不存在或不属于当前用户")
        return question

    def _resolve_ai_config(self, call_ai_dto: CallAiDto) -> AiRuntimeConfig:
        """解密用户 AI 配置并确定模型，转成运行时凭据对象。"""

        with_result = UserAiConfigMapper(self.db).get_by_id(
            call_ai_dto.user_config_id,
            call_ai_dto.user_id,
        )
        configured_models = list(with_result.model_names or [])
        if not configured_models:
            raise ValueError("AI 配置中没有可用模型")
        model_name = call_ai_dto.model_name or configured_models[0]
        if model_name not in configured_models:
            raise ValueError("所选模型不属于该 AI 配置")
        return AiRuntimeConfig(
            user_config_id=call_ai_dto.user_config_id,
            model_name=model_name,
            ai_url=with_result.ai_url,
            api_key=ApiKeyCrypto().decrypt(with_result.api_key),
            max_tokens=None,
        )

    def _persist_message(self, call_ai_dto: CallAiDto, role: str, content: str) -> None:
        """写入一条 USER/ASSISTANT 消息（前端历史列表仍然从 MySQL 读取）。"""

        from datetime import datetime

        now = datetime.now()
        self.agent_message_mapper.add_agent_message(
            AgentMessage(
                agent_conversation_id=call_ai_dto.conversation_id,
                user_id=call_ai_dto.user_id,
                role=role,
                content=content,
                create_time=now,
                update_time=now,
            )
        )

    def _context_replay(self, call_ai_dto: CallAiDto) -> str | None:
        """取本会话最近若干轮消息拼成前情提要，供新运行时实例的首轮使用。

        历史的事实来源是 MySQL（前端历史列表也读它）：dsh 的 JSONL 会话日志无法跨进程
        恢复（恢复判定要求 seed 逐字节相等，见 runtime.build_session_id），实例重建后
        模型上下文为空，靠这段提要把它接回去。
        """

        messages = self.agent_message_mapper.get_agent_message_20(
            call_ai_dto.conversation_id, call_ai_dto.user_id
        )
        return build_context_replay(messages)

    def stream_turn(
        self,
        call_ai_dto: CallAiDto,
        token: str,
        cancel_event: threading.Event | None = None,
    ) -> Iterator[tuple[str, dict[str, Any]]]:
        """执行一轮对话，实时产出 ``(sse 事件名, 数据帧)`` 元组。

        事件名与 dsh 会话事件词汇对齐：过程帧统一为 ``session.event``，
        终止帧为 ``done`` / ``error``。

        运行时子进程在同会话上一轮未结束时拒绝新的轮次（RuntimeBusyError）。
        """

        question = self._validate(call_ai_dto)
        ai_config = self._resolve_ai_config(call_ai_dto)
        manager = get_runtime_manager()
        cancel_event = cancel_event or threading.Event()
        try:
            owner = manager.claim_turn(call_ai_dto.conversation_id)
        except RuntimeBusyError as exc:
            self._handle_failure(call_ai_dto.conversation_id, exc, ai_config)
            yield ("error", error_payload(call_ai_dto.conversation_id, _user_message(exc)))
            return
        try:
            runtime = manager.obtain(call_ai_dto.conversation_id, ai_config)
        except BaseException:
            manager.release_turn(call_ai_dto.conversation_id, owner)
            raise

        # 新实例首轮：模型上下文为空，用 MySQL 里的历史把上下文接回去。必须在请求线程
        # 里读库（SQLAlchemy 会话非线程安全，worker 线程只跑运行时）。
        history = self._context_replay(call_ai_dto) if runtime.needs_context_replay else None

        events: queue.Queue[tuple[str, Any]] = queue.Queue(maxsize=128)
        worker_done = threading.Event()

        def enqueue(kind: str, payload: Any) -> None:
            while not cancel_event.is_set():
                try:
                    events.put((kind, payload), timeout=0.1)
                    return
                except queue.Full:
                    continue

        def on_event(event: dict[str, Any]) -> None:
            enqueue("event", event)

        def drive() -> None:
            try:
                result = runtime.run_turn(question, token, on_event, cancel_event=cancel_event,
                                          history=history)
                enqueue("done", result)
            except BaseException as exc:  # noqa: BLE001 - 必须把失败传回消费线程
                enqueue("error", exc)
            finally:
                worker_done.set()

        worker = threading.Thread(target=drive, name=f"dsh-turn-{call_ai_dto.conversation_id}", daemon=True)
        worker.start()
        terminal_received = False
        deadline = time.monotonic() + getattr(runtime, "turn_timeout_seconds", 300)
        committed = False

        try:
            while True:
                if cancel_event.is_set():
                    return
                if time.monotonic() >= deadline:
                    exc = TimeoutError("Agent 整轮执行超时")
                    self._handle_failure(call_ai_dto.conversation_id, exc, ai_config)
                    yield ("error", error_payload(call_ai_dto.conversation_id, AgentErrorCode.TURN_TIMEOUT.message))
                    return
                try:
                    kind, payload = events.get(timeout=0.2)
                except queue.Empty:
                    continue
                if kind == "event":
                    yield ("session.event", sse_payload(call_ai_dto.conversation_id, payload))
                    continue
                terminal_received = True
                if kind == "done":
                    response_text = payload.final_response
                    finish_reason = payload.finish_reason
                    if not response_text or finish_reason != "completed":
                        logger.error(
                            "Agent 无有效完成结果 conversation_id=%s config_id=%s model=%s finish_reason=%s upstream=%s",
                            call_ai_dto.conversation_id, ai_config.user_config_id,
                            ai_config.model_name, finish_reason,
                            _upstream_failure_detail(getattr(payload, "events", None))
                            or "(上游未返回错误正文)",
                        )
                        manager.drop(call_ai_dto.conversation_id, invalidate_only=False)
                        yield ("error", error_payload(call_ai_dto.conversation_id, AgentErrorCode.EMPTY_RESPONSE.message))
                        return
                    self._persist_message(call_ai_dto, "USER", question)
                    self._persist_message(call_ai_dto, "ASSISTANT", response_text)
                    self.db.commit()
                    committed = True
                    yield ("done", done_payload(call_ai_dto.conversation_id, finish_reason, response_text))
                    return

                self._handle_failure(call_ai_dto.conversation_id, payload, ai_config)
                yield ("error", error_payload(call_ai_dto.conversation_id, _user_message(payload)))
                return
        except BaseException:
            self.db.rollback()
            if terminal_received and not committed:
                manager.drop(call_ai_dto.conversation_id, invalidate_only=False)
            raise
        finally:
            if cancel_event.is_set() or not terminal_received:
                cancel_event.set()
                manager.drop(call_ai_dto.conversation_id, invalidate_only=False)
                worker.join(timeout=5)
                logger.info("Agent 轮次已取消 conversation_id=%s worker_stopped=%s",
                            call_ai_dto.conversation_id, not worker.is_alive())
            if worker.is_alive():
                def release_when_stopped() -> None:
                    worker.join()
                    manager.release_turn(call_ai_dto.conversation_id, owner)

                threading.Thread(target=release_when_stopped, daemon=True,
                                 name=f"dsh-cleanup-{call_ai_dto.conversation_id}").start()
            else:
                manager.release_turn(call_ai_dto.conversation_id, owner)

    def _handle_failure(self, conversation_id: int, exc: BaseException, ai_config: AiRuntimeConfig) -> None:
        """故障时重建运行时，并保留跨线程传递的原始异常堆栈。"""

        if isinstance(exc, RuntimeBusyError):
            logger.warning("会话并发请求被拒绝 conversation_id=%s", conversation_id)
            return
        logger.error(
            "Agent 轮次失败 conversation_id=%s config_id=%s model=%s exception_type=%s",
            conversation_id, ai_config.user_config_id, ai_config.model_name, type(exc).__name__,
            exc_info=(type(exc), exc, exc.__traceback__),
        )
        get_runtime_manager().drop(conversation_id, invalidate_only=False)


def _user_message(exc: BaseException) -> str:
    """把内部异常翻译成面向用户的安全文案（不携带堆栈与密钥）。"""

    if isinstance(exc, RuntimeBusyError):
        return AgentErrorCode.TURN_BUSY.message
    if isinstance(exc, TransportClosedError):
        return AgentErrorCode.RUNTIME_INTERRUPTED.message
    return AgentErrorCode.AGENT_CALL_FAILED.message


_CONTEXT_REPLAY_HEADER = (
    "【本会话早前的对话记录，仅供你延续上下文：其中的问题都已回答过，"
    "不要重复回答，也不要把它当成新的用户输入】"
)


def build_context_replay(messages: list[Any], char_budget: int = 12000) -> str | None:
    """把历史消息拼成前情提要：从最近往前取，按字符预算收口。

    预算是必要的：系统提示词 + 51 个网关工具定义已经占掉约 1.2 万 token，回放再多会把
    上下文窗口挤满、提前触发压缩甚至超窗。字符数按中文约 1 token/字粗估，故取 12000。

    Returns:
        形如「【本会话早前的对话记录…】\\n用户：…\\n助手：…」的文本；没有可用消息时 None。
    """

    lines: list[str] = []
    used = 0
    for message in reversed(list(messages)):
        content = str(getattr(message, "content", "") or "").strip()
        if not content:
            continue
        role = str(getattr(message, "role", "") or "").upper()
        line = f"{'用户' if role == 'USER' else '助手'}：{content}"
        # 至少保留最近一条，避免单条超预算时把整段提要变成空。
        if lines and used + len(line) > char_budget:
            break
        lines.append(line)
        used += len(line)
    if not lines:
        return None
    lines.reverse()
    return "\n".join([_CONTEXT_REPLAY_HEADER, *lines])


def _upstream_failure_detail(events: Any, limit: int = 500) -> str | None:
    """从 dsh 会话事件里提取模型侧失败的上游原文，只用于日志留痕。

    dsh 把上游响应正文放在 ``turn/end`` 的 ``reason.error.message``（例如
    ``503: {"code":"model_not_found", ...}``）；单步失败则出现在 ``assistant/chunk``
    的 ``chunk.finish.reason.failure.message``。此前只记录 finish_reason，上游原话被
    丢弃，用户看到「模型未返回有效内容」时运维侧无从判断是模型名、额度还是中转故障。
    """

    detail: str | None = None
    for event in events if isinstance(events, (list, tuple)) else ():
        if not isinstance(event, dict):
            continue
        data = event.get("data")
        if not isinstance(data, dict):
            continue
        reason = data.get("reason")
        if not isinstance(reason, dict):
            chunk = data.get("chunk")
            finish = chunk.get("finish") if isinstance(chunk, dict) else None
            reason = finish.get("reason") if isinstance(finish, dict) else None
        if not isinstance(reason, dict):
            continue
        for key in ("error", "failure"):
            node = reason.get(key)
            if isinstance(node, dict) and node.get("message"):
                detail = str(node["message"])
    if detail is None:
        return None
    return detail if len(detail) <= limit else detail[:limit] + "…"
