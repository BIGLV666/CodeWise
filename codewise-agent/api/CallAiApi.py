"""Agent 对话 API：非流式与 SSE 流式入口。

SSE 事件契约（与 dsh 会话事件词汇对齐，前端可按 dsh Web UI 的词汇渲染）：

- ``session.event``：透传的 dsh 会话事件，data 形如
  ``{"conversation_id": 1, "event": {"type": "assistant/chunk", "data": {...}}}``；
  常见类型：assistant/chunk（回答增量）、tool/call（工具开始）、
  tool/result（工具结束）、turn/start、turn/end、compaction/*（记忆压缩）。
- ``done``：本轮正常结束，携带 finish_reason 与最终回答全文。
- ``error``：本轮失败，message 面向用户。
"""

import json
from collections.abc import Iterator
import logging

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse

from util.error_codes import AgentErrorCode

from dsh_bridge.service import DshAgentService
from dto.CallAiDto import CallAiDto
from dto.CallAiRequest import CallAiRequest
from entry.database import get_db
from util.jwt import get_current_user_id, get_token_from_header

router = APIRouter(prefix="/api/agent", tags=["Agent"])

logger = logging.getLogger(__name__)


def build_call_dto(request: CallAiRequest, user_id: int) -> CallAiDto:
    return CallAiDto(
        user_config_id=request.user_config_id,
        user_id=user_id,
        question=request.question,
        conversation_id=request.conversation_id,
        model_name=request.model_name,
    )


def sse_event(event: str, data: dict) -> str:
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n"


@router.post("/call")
def call_ai(
    request: CallAiRequest,
    user_id: int = Depends(get_current_user_id),
    token: str = Depends(get_token_from_header),
):
    """非流式对话：执行一轮并返回最终回答。"""

    try:
        with get_db() as session:
            service = DshAgentService(session)
            final = ""
            for event_name, payload in service.stream_turn(build_call_dto(request, user_id), token):
                if event_name == "done":
                    final = str(payload.get("content", ""))
                elif event_name == "error":
                    raise ValueError(str(payload.get("message", "Agent 调用失败")))
        return {
            "code": 200,
            "message": "success",
            "data": {
                "conversation_id": request.conversation_id,
                "content": final,
            },
        }
    except LookupError as exc:
        raise HTTPException(
            status_code=404,
            detail=AgentErrorCode.CONVERSATION_NOT_FOUND.message,
        ) from exc
    except ValueError as exc:
        logger.warning("Agent 调用失败: %s", exc)
        raise HTTPException(
            status_code=400,
            detail=AgentErrorCode.AGENT_CALL_FAILED.message,
        ) from exc


@router.post("/stream")
def stream_ai(
    request: CallAiRequest,
    user_id: int = Depends(get_current_user_id),
    token: str = Depends(get_token_from_header),
) -> StreamingResponse:
    """SSE 流式对话：实时转发 dsh 会话事件。"""

    call_ai_dto = build_call_dto(request, user_id)

    def event_generator() -> Iterator[str]:
        try:
            # Session 必须由生成器持有。流式响应真正迭代结束后才会离开 with，
            # 此时 get_db 才统一 commit；中途异常则 rollback。
            with get_db() as session:
                service = DshAgentService(session)
                for event_name, payload in service.stream_turn(call_ai_dto, token):
                    yield sse_event(event_name, payload)

        except Exception as exc:
            # HTTP 响应开始后不能再改变状态码，因此通过 error 事件通知前端。
            # 事件文案固定来自枚举，原始异常只进日志。
            logger.exception("Agent 流式调用失败: %s", exc)
            yield sse_event(
                "error",
                {
                    "conversation_id": request.conversation_id,
                    "message": AgentErrorCode.AGENT_CALL_FAILED.message,
                },
            )

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )
