"""dsh 运行时端到端冒烟脚本（真实 Node 运行时 + mock 模型端点 + mock 内网网关）。

验证链路：
1. agent-runtime/cordis.yml 组合能真实启动（含 codewise-tools 插件装载）；
2. llm-deepseek 适配器对接 OpenAI 兼容 SSE 端点（thinking: disabled）；
3. 模型发起 tool_call → dsh 工具管线 → 插件读 token 文件 → mock 网关返回
   Result 包装 → 结构化 tool/result 回到模型 → 最终文本回答；
4. JSONL 会话日志落盘，第二轮同 session 恢复。

用法：.venv/Scripts/python.exe tests/smoke_dsh_runtime.py
不依赖 MySQL、Java 服务或真实模型 Key。
"""

from __future__ import annotations

import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))

# 冒烟所需最小环境（在导入 dsh_bridge 前设置）
os.environ.setdefault("CODEWISE_GATEWAY_URL", "http://127.0.0.1:1")  # 会被运行时 env 覆盖

from dsh_bridge.sdk import DeepSeekHarness, DeepSeekHarnessConfig  # noqa: E402


class JsonHandler(BaseHTTPRequestHandler):
    """简单的 JSON/SSE 双面 mock 服务器基类。"""

    def log_message(self, *args):  # 静默访问日志
        pass

    def _send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class MockGateway(JsonHandler):
    """mock CodeWise 网关：记录 Authorization，返回固定用户信息。"""

    seen_auth: list[str] = []

    def do_GET(self):  # noqa: N802
        MockGateway.seen_auth.append(self.headers.get("Authorization", ""))
        self._send_json(
            {"code": 200, "message": "success", "data": {"userId": 101, "userName": "冒烟用户"}}
        )


class MockModel(JsonHandler):
    """mock OpenAI 兼容端点：第 1 次请求返回 tool_call，第 2 次返回文本。"""

    request_count = 0
    last_model: str | None = None

    def do_POST(self):  # noqa: N802
        if "chat/completions" not in self.path:
            self._send_json({"error": "not found"}, status=404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        MockModel.request_count += 1
        MockModel.last_model = body.get("model")

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def chunk(delta: dict, finish: str | None, usage: dict | None = None) -> str:
            payload: dict = {
                "id": "chatcmpl-smoke",
                "object": "chat.completion.chunk",
                "created": 1,
                "model": body.get("model", "test-model"),
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            }
            if usage is not None:
                payload["usage"] = usage
            return f"data: {json.dumps(payload)}\n\n"

        if MockModel.request_count == 1:
            # 第一轮：要求调用 get_user_info
            self.wfile.write(
                chunk(
                    {
                        "role": "assistant",
                        "tool_calls": [
                            {
                                "index": 0,
                                "id": "call_smoke_1",
                                "type": "function",
                                "function": {"name": "get_user_info", "arguments": "{}"},
                            }
                        ],
                    },
                    None,
                ).encode()
            )
            self.wfile.write(
                chunk({}, "tool_calls", {"prompt_tokens": 50, "completion_tokens": 10, "total_tokens": 60}).encode()
            )
        else:
            # 第二轮：基于工具结果回答
            self.wfile.write(chunk({"role": "assistant", "content": "你是冒烟用户"}, None).encode())
            self.wfile.write(
                chunk({}, "stop", {"prompt_tokens": 90, "completion_tokens": 8, "total_tokens": 98}).encode()
            )
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


def start_server(handler) -> tuple[ThreadingHTTPServer, int]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, server.server_address[1]


def main() -> int:
    tmp = Path(__file__).parent / ".smoke-tmp"
    # 清理上次运行残留（同 id 会话日志会造成 id collision）
    import shutil

    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(exist_ok=True)
    session_root = tmp / "sessions"
    session_root.mkdir(exist_ok=True)
    token_file = tmp / "smoke.token"
    token_file.write_text("smoke-bearer-token", encoding="utf-8")

    gw_server, gw_port = start_server(MockGateway)
    model_server, model_port = start_server(MockModel)

    runtime_entry = (
        REPO_ROOT
        / "agent-runtime"
        / "node_modules"
        / "@deepseek-ai"
        / "dsh-sdk-jsonrpc-demo"
        / "lib"
        / "packaged-bin.js"
    )
    env = {
        "DSH_CORDIS_CONFIG": str(REPO_ROOT / "agent-runtime" / "cordis.yml"),
        "DSH_SYSTEM_PROMPT": "你是冒烟测试助手。",
        "DSH_CONTEXT_WINDOW": "16384",
        "DSH_MODEL_ID": "test-model",
        "CODEWISE_GATEWAY_URL": f"http://127.0.0.1:{gw_port}",
        "CODEWISE_TOKEN_FILE": str(token_file),
    }
    config = DeepSeekHarnessConfig(
        provider="codewise",
        model="test-model",
        cwd=str(session_root),
        runtime_cwd=str(REPO_ROOT / "agent-runtime"),
        session_root=str(session_root),
        env=env,
        base_url=f"http://127.0.0.1:{model_port}",
        # mock 端点不校验密钥，这里仅验证 env 注入链路（避免书写密钥样式字符串）
        api_key=os.environ.get("SMOKE_MODEL_API_KEY", "mock-key"),
        launch_args_override=("node", str(runtime_entry)),
    )

    events: list[dict] = []

    def on_event(notification) -> None:
        # SDK 回调传原始 Notification；只收集根会话的 session.event。
        if notification.method != "session.event":
            return
        event = notification.payload.get("event")
        if isinstance(event, dict):
            events.append(event)
            data = json.dumps(event.get("data"), ensure_ascii=False)
            print(f"  [event] {event.get('type')} {data[:600]}")

    try:
        harness = DeepSeekHarness(config)
        harness.start()
        print("1) 运行时已启动，发起第一轮（应触发 get_user_info 工具）...")
        result = harness.run("查询我的用户信息", session_id="smoke-1", on_notification=on_event)
        print(f"   final_response: {result.final_response!r}")
        print(f"   finish_reason: {result.finish_reason!r}")

        event_types = [e.get("type") for e in events]
        tool_calls = [e for e in events if e.get("type") == "tool/call"]
        tool_results = [e for e in events if e.get("type") == "tool/result"]
        assert result.final_response == "你是冒烟用户", f"最终回答不符: {result.final_response!r}"
        assert any(t and "tool/call" in t for t in event_types), f"缺少 tool/call 事件: {event_types}"
        assert tool_calls and tool_calls[0]["data"].get("name") == "get_user_info"
        assert tool_results, "缺少 tool/result 事件"

        assert MockGateway.seen_auth, "mock 网关没有被调用"
        assert MockGateway.seen_auth[-1] == "Bearer smoke-bearer-token", (
            f"Authorization 头不符: {MockGateway.seen_auth[-1]!r}"
        )
        assert MockModel.last_model == "test-model", f"模型 id 未透传: {MockModel.last_model!r}"

        session_files = list(session_root.rglob("*.jsonl"))
        assert session_files, "JSONL 会话日志未落盘"
        print(f"2) 会话日志: {[p.name for p in session_files]}")

        # 第二轮：同 session 续接（验证 JSONL 恢复）
        events.clear()
        result2 = harness.run("再来一次", session_id="smoke-1", on_notification=on_event)
        assert result2.final_response == "你是冒烟用户"
        print("3) 第二轮同 session 恢复成功")
        harness.close()

        print("\nSMOKE OK: cordis 组合、插件装载、工具管线、网关调用、会话持久化全部通过")
        return 0
    finally:
        gw_server.shutdown()
        model_server.shutdown()


if __name__ == "__main__":
    raise SystemExit(main())
