"""SSE 帧构造器。"""

from __future__ import annotations

from dsh_bridge.bridge import done_payload, error_payload, sse_payload


def test_sse_payload_passes_dsh_event_through():
    event = {"type": "assistant/chunk", "data": {"text": "x"}}
    frame = sse_payload(1, event)
    assert frame == {"conversation_id": 1, "event": event}


def test_done_payload_carries_content_and_reason():
    frame = done_payload(2, "completed", "回答")
    assert frame == {"conversation_id": 2, "finish_reason": "completed", "content": "回答"}


def test_error_payload_sanitizes_empty_message():
    frame = error_payload(3, "")
    assert frame["message"] == "Agent 调用失败"
