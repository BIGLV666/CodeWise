"""Agent 门面服务：把一轮对话落到 dsh 运行时，并把过程桥接为 SSE 事件流。

职责：
1. 校验请求与会话归属，解析并解密用户 AI 配置；
2. 持久化 USER / ASSISTANT 消息（前端历史列表数据源，与旧版一致）；
3. 通过 RuntimeManager 取得会话运行时，执行一轮对话并实时转发 dsh 事件；
4. 终止帧（done / error）与运行时故障恢复（子进程丢弃后按 JSONL 会话重建）。

dsh 侧负责的：agent 循环、工具执行、JSONL 会话日志与自动压缩。
"""

from __future__ import annotations

import logging
import queue
import threading
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

    def stream_turn(
        self,
        call_ai_dto: CallAiDto,
        token: str,
    ) -> Iterator[tuple[str, dict[str, Any]]]:
        """执行一轮对话，实时产出 ``(sse 事件名, 数据帧)`` 元组。

        事件名与 dsh 会话事件词汇对齐：过程帧统一为 ``session.event``，
        终止帧为 ``done`` / ``error``。

        运行时子进程在同会话上一轮未结束时拒绝新的轮次（RuntimeBusyError）。
        """

        question = self._validate(call_ai_dto)
        ai_config = self._resolve_ai_config(call_ai_dto)
        self._persist_message(call_ai_dto, "USER", question)

        manager = get_runtime_manager()
        runtime = manager.obtain(call_ai_dto.conversation_id, ai_config)

        events: queue.Queue[tuple[str, Any]] = queue.Queue()

        def on_event(event: dict[str, Any]) -> None:
            events.put(("event", event))

        def drive() -> None:
            try:
                result = runtime.run_turn(question, token, on_event)
                events.put(("done", result))
            except BaseException as exc:  # noqa: BLE001 - 必须把失败传回消费线程
                events.put(("error", exc))

        worker = threading.Thread(target=drive, name=f"dsh-turn-{call_ai_dto.conversation_id}", daemon=True)
        worker.start()

        while True:
            kind, payload = events.get()
            if kind == "event":
                yield ("session.event", sse_payload(call_ai_dto.conversation_id, payload))
                continue
            if kind == "done":
                response_text = payload.final_response
                finish_reason = payload.finish_reason
                if not response_text:
                    # turn 已结束但没有可展示文本：按错误收尾，避免前端拿到空回答。
                    manager.drop(call_ai_dto.conversation_id, invalidate_only=True)
                    yield ("error", error_payload(call_ai_dto.conversation_id, "模型未返回有效内容"))
                    break
                self._persist_message(call_ai_dto, "ASSISTANT", response_text)
                yield ("done", done_payload(call_ai_dto.conversation_id, finish_reason, response_text))
                break

            # kind == "error"
            self._handle_failure(call_ai_dto.conversation_id, payload)
            yield ("error", error_payload(call_ai_dto.conversation_id, _user_message(payload)))
            break

    def _handle_failure(self, conversation_id: int, exc: BaseException) -> None:
        """运行时通信故障时丢弃子进程，下一轮自动按 JSONL 会话重建。"""

        if isinstance(exc, TransportClosedError):
            logger.error("dsh 运行时中断 conversation_id=%s：%s", conversation_id, exc)
            get_runtime_manager().drop(conversation_id, invalidate_only=True)
        elif isinstance(exc, RuntimeBusyError):
            logger.warning("会话并发请求被拒绝 conversation_id=%s", conversation_id)
        else:
            logger.exception("Agent 轮次失败 conversation_id=%s", conversation_id)


def _user_message(exc: BaseException) -> str:
    """把内部异常翻译成面向用户的安全文案（不携带堆栈与密钥）。"""

    if isinstance(exc, RuntimeBusyError):
        return "上一轮对话还在进行中，请稍候再发送新消息"
    if isinstance(exc, TransportClosedError):
        return "Agent 服务中断，请重试"
    if isinstance(exc, (ValueError, LookupError)):
        return str(exc)
    return "Agent 调用失败，请稍后重试"
