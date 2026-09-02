"""dsh session event → SSE 事件的桥接。

设计原则：**透传 dsh 的原生事件词汇**。dsh 的会话事件类型（assistant/chunk、
tool/call、tool/result、turn/end、compaction/* 等）是有文档的稳定契约，前端
复刻 dsh Web UI 时可以直接按同一套词汇渲染工具卡片与思考流，桥接层不做
有损翻译。

SSE 帧格式（api 层负责序列化）::

    event: session.event
    data: {"conversation_id": 1, "event": {"type": "assistant/chunk", "data": {...}}}

终止帧::

    event: done   data: {"conversation_id": 1, "finish_reason": "completed", "content": "..."}
    event: error  data: {"conversation_id": 1, "message": "..."}
"""

from __future__ import annotations

from typing import Any


def sse_payload(conversation_id: int, event: dict[str, Any]) -> dict[str, Any]:
    """构造一条透传的 session.event 数据帧。"""

    return {"conversation_id": conversation_id, "event": event}


def done_payload(
    conversation_id: int,
    finish_reason: str | None,
    content: str,
) -> dict[str, Any]:
    """构造正常结束帧。

    Args:
        conversation_id: 会话主键。
        finish_reason: dsh turn/end 的 reason.kind（如 completed）。
        content: 最终回答全文（同时用于响应体与非流式接口）。
    """

    return {"conversation_id": conversation_id, "finish_reason": finish_reason, "content": content}


def error_payload(conversation_id: int, message: str) -> dict[str, Any]:
    """构造错误帧；message 面向最终用户，不携带内部堆栈与密钥。"""

    return {"conversation_id": conversation_id, "message": message or "Agent 调用失败"}
