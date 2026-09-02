"""dsh 运行时 web/code 工具端到端冒烟（真实 Node 运行时 + mock 模型端点）。

验证链路（在 smoke_dsh_runtime.py 的基础上扩展）：
1. cordis.yml 挂载 web/tool-web/codewise-web/code-runtime 后能真实启动；
2. 模型可见的工具清单 = 11 个 codewise-tools + web_fetch + run_code（无搜索 key
   时不注册 web_search，也不存在 bash/fs 等内置工具）；
3. web_fetch 对内网地址（127.0.0.1）被 SSRF 守卫拒绝（结构化错误）；
4. web_fetch 对公网地址成功返回正文（需外网）；
5. run_code 执行 TypeScript 程序并返回结果。

用法：.venv/Scripts/python.exe tests/smoke_web_tools.py
不依赖 MySQL、Java 服务或真实模型 Key；第 4 步需要外网。
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

os.environ.setdefault("CODEWISE_GATEWAY_URL", "http://127.0.0.1:1")

from dsh_bridge.sdk import DeepSeekHarness, DeepSeekHarnessConfig  # noqa: E402


class JsonHandler(BaseHTTPRequestHandler):
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
    """mock 内网网关：web_fetch 若守卫失效会请求到它，从而暴露 SSRF 漏洞。"""

    hit = 0

    def do_GET(self):  # noqa: N802
        MockGateway.hit += 1
        self._send_json({"code": 200, "message": "success", "data": {"leak": True}})


class MockModel(JsonHandler):
    """mock OpenAI 兼容端点：按轮次依次驱动 web_fetch(内网)/web_fetch(公网)/run_code。"""

    request_count = 0
    tools_seen: list[dict] = []

    def do_POST(self):  # noqa: N802
        if "chat/completions" not in self.path:
            self._send_json({"error": "not found"}, status=404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        MockModel.request_count += 1
        if MockModel.request_count == 1:
            MockModel.tools_seen = body.get("tools") or []

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def chunk(delta: dict, finish: str | None) -> str:
            payload: dict = {
                "id": "chatcmpl-smoke-web",
                "object": "chat.completion.chunk",
                "created": 1,
                "model": body.get("model", "test-model"),
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            }
            return f"data: {json.dumps(payload)}\n\n"

        def tool_call(name: str, args: dict) -> str:
            return chunk(
                {
                    "role": "assistant",
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": f"call_{MockModel.request_count}",
                            "type": "function",
                            "function": {"name": name, "arguments": json.dumps(args)},
                        }
                    ],
                },
                None,
            )

        gw_port = os.environ["SMOKE_GW_PORT"]
        if MockModel.request_count == 1:
            self.wfile.write(tool_call("web_fetch", {"url": f"http://127.0.0.1:{gw_port}/internal"}).encode())
        elif MockModel.request_count == 2:
            self.wfile.write(tool_call("web_fetch", {"url": "https://example.com/"}).encode())
        elif MockModel.request_count == 3:
            self.wfile.write(tool_call("run_code", {"code": "return 1 + 2", "description": "计算 1+2"}).encode())
        else:
            self.wfile.write(chunk({"role": "assistant", "content": "web 工具冒烟完成"}, None).encode())
        self.wfile.write(chunk({}, "tool_calls" if MockModel.request_count <= 3 else "stop").encode())
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


def start_server(handler) -> tuple[ThreadingHTTPServer, int]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, server.server_address[1]


def tool_names(tools: list[dict]) -> set[str]:
    names: set[str] = set()
    for tool in tools:
        fn = tool.get("function") if isinstance(tool, dict) else None
        if isinstance(fn, dict) and isinstance(fn.get("name"), str):
            names.add(fn["name"])
    return names


def main() -> int:
    tmp = Path(__file__).parent / ".smoke-web-tmp"
    import shutil

    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(exist_ok=True)
    session_root = tmp / "sessions"
    session_root.mkdir(exist_ok=True)
    token_file = tmp / "smoke.token"
    token_file.write_text("smoke-bearer-token", encoding="utf-8")

    gw_server, gw_port = start_server(MockGateway)
    model_server, model_port = start_server(MockModel)
    os.environ["SMOKE_GW_PORT"] = str(gw_port)

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
        # 不配置 EXA/PERPLEXITY key：验证 web_search 不注册。
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
        if notification.method != "session.event":
            return
        event = notification.payload.get("event")
        if isinstance(event, dict):
            events.append(event)

    try:
        harness = DeepSeekHarness(config)
        harness.start()
        result = harness.run("用 web 工具做一轮冒烟", session_id="smoke-web-1", on_notification=on_event)

        # 1) 工具清单：11 个网关工具 + web_fetch + run_code，无 web_search、无 bash/fs。
        names = tool_names(MockModel.tools_seen)
        expected_codewise = {
            "get_current_time", "get_user_info", "search_question", "get_question_by_id",
            "get_recent_submissions", "get_submission_by_id", "get_submissions_by_ids",
            "get_review_record", "get_user_review_config", "get_all_review", "update_review_config",
        }
        assert expected_codewise <= names, f"网关工具缺失: {expected_codewise - names}"
        assert "web_fetch" in names, f"缺少 web_fetch: {sorted(names)}"
        assert "run_code" in names, f"缺少 run_code: {sorted(names)}"
        assert "web_search" not in names, "无搜索 key 时不应注册 web_search"
        assert not ({"bash", "read_file", "write_file", "subagent", "todo"} & names), (
            f"意外暴露内置工具: {sorted(names)}"
        )
        print(f"1) 工具清单通过：{len(names)} 个工具")

        # 2) 工具调用顺序与结果。
        calls = [e for e in events if e.get("type") == "tool/call"]
        results = [e for e in events if e.get("type") == "tool/result"]
        call_names = [c["data"].get("name") for c in calls if isinstance(c.get("data"), dict)]
        assert call_names == ["web_fetch", "web_fetch", "run_code"], f"调用顺序不符: {call_names}"

        # 3) 第一次 web_fetch（内网）必须被守卫拒绝，且 mock 网关未被命中。
        assert MockGateway.hit == 0, f"SSRF 守卫失效：内网网关被请求 {MockGateway.hit} 次"

        def result_info(data: dict) -> tuple[bool, str, str | None]:
            """从 tool/result 事件数据里抽取 isError、渲染文本与错误码。"""
            blocks = (data.get("message") or {}).get("content") or []
            is_error, text, code = None, "", None
            for block in blocks:
                if isinstance(block, dict) and block.get("type") == "tool-result":
                    is_error = bool(block.get("isError"))
                    inner = block.get("content") or []
                    text = "".join(x.get("text", "") for x in inner if isinstance(x, dict))
            err = data.get("error")
            if isinstance(err, dict):
                code = err.get("code")
            return is_error, text, code

        infos = [result_info(r.get("data") or {}) for r in results]
        assert len(infos) == 3, f"tool/result 数量不符: {len(infos)}"

        # 第 1 次：内网 web_fetch 被拒绝，错误码 WEB_BLOCKED_URL。
        is_error, text, code = infos[0]
        assert is_error is True and code == "WEB_BLOCKED_URL", f"内网请求未被守卫拒绝: {infos[0]}"
        assert ("非公网" in text) or ("禁止" in text), f"拒绝信息不明确: {text[:200]}"
        print("2) 内网 web_fetch 被 SSRF 守卫拒绝（mock 网关零命中）")

        # 第 2 次：公网 web_fetch 成功返回正文。
        is_error, text, _ = infos[1]
        assert is_error is False, f"公网 web_fetch 出错: {text[:200]}"
        assert "example.com" in text, f"公网抓取结果不含目标域名: {text[:200]}"
        print("3) 公网 web_fetch 成功返回正文")

        # 第 3 次：run_code 执行成功，结果含 3。
        is_error, text, _ = infos[2]
        assert is_error is False, f"run_code 执行失败: {text[:200]}"
        assert "3" in text, f"run_code 结果不含 3: {text[:200]}"
        print("4) run_code 执行 TypeScript 程序成功")

        assert result.final_response == "web 工具冒烟完成", f"最终回答不符: {result.final_response!r}"
        harness.close()

        print("\nSMOKE WEB OK: 工具清单、SSRF 守卫、公网抓取、代码执行全部通过")
        return 0
    finally:
        gw_server.shutdown()
        model_server.shutdown()


if __name__ == "__main__":
    raise SystemExit(main())
