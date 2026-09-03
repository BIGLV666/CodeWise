"""dsh 运行时 web/code 工具端到端冒烟（真实 Node 运行时 + mock 模型端点）。

验证链路（在 smoke_dsh_runtime.py 的基础上扩展）：
1. cordis.yml 挂载 web/tool-web/codewise-web/code-runtime 后能真实启动；
2. 模型可见的工具清单 = 51 个 codewise-tools + web_fetch + run_code（无搜索 key
   时不注册 web_search，也不存在 bash/fs 等内置工具）；
3. web_fetch 对内网地址（127.0.0.1）被 SSRF 守卫拒绝（结构化错误）；
4. web_fetch 对公网地址成功返回正文（需外网）；
5. run_code 执行 TypeScript 程序并返回结果；
6. 收藏夹/社区工具族（列表/批量收藏/批量详情/显式点赞）经 mock 网关走通 tool_call→tool/result，
   并携带正确的 Bearer 鉴权头。

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
    """mock 内网网关：记录命中路径与鉴权头；web_fetch 若守卫失效会请求到它，从而暴露 SSRF 漏洞。"""

    hit = 0
    paths: list[str] = []
    auth_headers: list[str | None] = []

    def _record(self) -> None:
        MockGateway.hit += 1
        MockGateway.paths.append(self.path)
        MockGateway.auth_headers.append(self.headers.get("Authorization"))

    def do_GET(self):  # noqa: N802
        self._record()
        if self.path.startswith("/api/review/agent/favorites/locate"):
            self._send_json({"code": 200, "message": "success", "data": [
                {"questionId": 1, "folders": [{"favoriteId": 101, "favoritesName": "面经"}]},
            ]})
        elif self.path.startswith("/api/review/agent/favorites"):
            self._send_json({"code": 200, "message": "success", "data": [
                {"favoritesId": 101, "favoritesName": "面经", "favoritesType": "算法",
                 "favoritesContent": "描述", "questionCount": 2,
                 "createTime": "2026-09-03T10:00:00", "updateTime": "2026-09-03T10:00:00"},
            ]})
        elif self.path.startswith("/api/community/agent/posts"):
            self._send_json({"code": 200, "message": "success", "data": {
                "records": [{"postId": 101, "postTitle": "帖1", "tags": ["算法"],
                             "userId": "7", "userName": "冒烟", "likeCount": 3,
                             "commentCount": 1, "createTime": "2026-09-03T10:00:00",
                             "updateTime": "2026-09-03T10:00:00"}],
                "nextCursor": 100, "hasNext": False, "total": None,
            }})
        elif self.path.startswith("/api/review/agent/progress/today"):
            self._send_json({"code": 200, "message": "success", "data": [
                {"progressId": 1, "questionId": 1, "title": "题1", "difficulty": 2,
                 "beginTime": "2026-09-03", "status": 1, "notesContent": "复习", "summaryContent": None},
            ]})
        elif self.path.startswith("/api/review/agent/note/folders"):
            self._send_json({"code": 200, "message": "success", "data": [
                {"folderId": 1, "folderName": "算法", "noteCount": 2,
                 "createTime": "2026-09-03T10:00:00", "updateTime": "2026-09-03T10:00:00"},
            ]})
        else:
            self._send_json({"code": 200, "message": "success", "data": {"leak": True}})

    def do_POST(self):  # noqa: N802
        self._record()
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path == "/api/review/agent/favorites/questions":
            qids = body.get("questionIds", [])
            invalid = [q for q in qids if q == 998]
            invisible = [q for q in qids if q == 999]
            self._send_json({"code": 200, "message": "success", "data": {
                "added": len(qids) - len(invisible) - len(invalid),
                "skippedDuplicateIds": [],
                "skippedInvisibleIds": invisible,
                "invalidQuestionIds": invalid,
            }})
        elif self.path == "/api/question/agent/detail":
            self._send_json({"code": 200, "message": "success", "data": [
                {"questionId": q, "state": "ok", "message": None,
                 "question": {"questionId": q, "title": f"题{q}"}}
                for q in body.get("questionIds", [])
            ]})
        elif self.path == "/api/community/agent/likes":
            self._send_json({"code": 200, "message": "success", "data": {"liked": True, "changed": True}})
        elif self.path == "/api/review/agent/progress/create":
            self._send_json({"code": 200, "message": "success", "data": {"created": 1, "skippedQuestionIds": []}})
        elif self.path == "/api/review/agent/note":
            self._send_json({"code": 200, "message": "success", "data": {
                "noteId": 1, "title": "题1笔记", "folderId": 1, "contentType": "MD",
                "content": "# 题1\n正文", "createTime": "2026-09-03T10:00:00",
                "updateTime": "2026-09-03T10:00:00",
            }})
        else:
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
        elif MockModel.request_count == 4:
            self.wfile.write(tool_call("list_favorite_folders", {}).encode())
        elif MockModel.request_count == 5:
            self.wfile.write(tool_call(
                "add_questions_to_favorite",
                {"favorite_id": 101, "question_ids": [1, 2, 999, 998]},
            ).encode())
        elif MockModel.request_count == 6:
            self.wfile.write(tool_call("get_question_details", {"question_ids": [1, 2]}).encode())
        elif MockModel.request_count == 7:
            self.wfile.write(tool_call("list_community_posts", {"order": "latest", "page_size": 10}).encode())
        elif MockModel.request_count == 8:
            self.wfile.write(tool_call(
                "like_community_target",
                {"target_type": "post", "target_id": 101, "action": "like"},
            ).encode())
        elif MockModel.request_count == 9:
            self.wfile.write(tool_call("get_today_plan", {}).encode())
        elif MockModel.request_count == 10:
            self.wfile.write(tool_call(
                "create_study_plan",
                {"items": [{"question_id": 1, "begin_time": "2026-09-03", "notes": "复习"}]},
            ).encode())
        elif MockModel.request_count == 11:
            self.wfile.write(tool_call("list_note_folders", {}).encode())
        elif MockModel.request_count == 12:
            self.wfile.write(tool_call(
                "create_note",
                {"title": "题1笔记", "content": "# 题1\n正文", "folder_id": 1},
            ).encode())
        else:
            self.wfile.write(chunk({"role": "assistant", "content": "web 工具冒烟完成"}, None).encode())
        self.wfile.write(chunk({}, "tool_calls" if MockModel.request_count <= 12 else "stop").encode())
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

        # 1) 工具清单：51 个网关工具 + web_fetch + run_code，无 web_search、无 bash/fs。
        names = tool_names(MockModel.tools_seen)
        expected_codewise = {
            "get_current_time", "get_user_info", "search_question", "get_question_by_id",
            "get_recent_submissions", "get_submission_by_id", "get_submissions_by_ids",
            "get_review_record", "get_user_review_config", "get_all_review", "update_review_config",
            "list_favorite_folders", "get_favorite_questions", "create_favorite_folder",
            "update_favorite_folder", "delete_favorite_folder", "add_questions_to_favorite",
            "remove_questions_from_favorite", "move_questions_to_favorite", "find_question_in_favorites",
            "get_question_details",
            "list_community_posts", "search_community_posts", "get_community_hot_posts",
            "get_community_post", "create_community_post", "update_community_post",
            "delete_community_post", "list_post_comments", "create_community_comment",
            "delete_community_comment", "like_community_target", "list_my_community_content",
            "get_today_plan", "list_study_plan", "get_plan_detail", "get_plan_calendar",
            "get_weekly_report", "create_study_plan", "update_study_plan", "delete_study_plan",
            "list_note_folders", "create_note_folder", "rename_note_folder", "delete_note_folder",
            "list_notes", "get_note", "create_note", "update_note", "move_note", "delete_note",
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
        assert call_names == [
            "web_fetch", "web_fetch", "run_code",
            "list_favorite_folders", "add_questions_to_favorite", "get_question_details",
            "list_community_posts", "like_community_target",
            "get_today_plan", "create_study_plan",
            "list_note_folders", "create_note",
        ], f"调用顺序不符: {call_names}"

        # 3) 第一次 web_fetch（内网）必须被守卫拒绝：探针路径零命中
        # （后续收藏夹工具轮次会合法命中 mock 网关，因此按路径断言而非总命中数）。
        assert not any(p.endswith("/internal") for p in MockGateway.paths), (
            f"SSRF 守卫失效：内网探针被请求: {MockGateway.paths}"
        )

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
        assert len(infos) == 12, f"tool/result 数量不符: {len(infos)}"

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

        # 第 4-6 次：收藏夹工具族经网关走通，且携带 Bearer 鉴权头。
        is_error, text, _ = infos[3]
        assert is_error is False, f"list_favorite_folders 失败: {text[:200]}"
        assert "面经" in text and '"favorites_id":101' in text, f"收藏夹列表内容不符: {text[:300]}"
        is_error, text, _ = infos[4]
        assert is_error is False, f"add_questions_to_favorite 失败: {text[:200]}"
        assert '"added":2' in text and '"skippedInvisibleIds":[999]' in text and '"invalidQuestionIds":[998]' in text, (
            f"批量收藏结果不符: {text[:300]}"
        )
        is_error, text, _ = infos[5]
        assert is_error is False, f"get_question_details 失败: {text[:200]}"
        assert '"state":"ok"' in text and "题1" in text and "题2" in text, f"批量详情结果不符: {text[:300]}"
        fav_paths = [p for p in MockGateway.paths if "/api/review/agent/favorites" in p or "/api/question/agent/detail" in p]
        assert len(fav_paths) == 3, f"收藏夹工具网关命中次数不符: {MockGateway.paths}"
        assert all(a == "Bearer smoke-bearer-token" for a in MockGateway.auth_headers[-9:]), (
            f"鉴权头缺失: {MockGateway.auth_headers[-9:]}"
        )
        print("5) 收藏夹工具族（列表/批量收藏/批量详情）经网关走通并携带 Bearer 头")

        # 第 7 次：社区帖子列表（latest 降序）走通并投影瘦身。
        is_error, text, _ = infos[6]
        assert is_error is False, f"list_community_posts 失败: {text[:200]}"
        assert '"post_title":"帖1"' in text and '"next_cursor":100' in text, f"帖子列表投影不符: {text[:300]}"
        # 第 8 次：显式点赞幂等接口走通。
        is_error, text, _ = infos[7]
        assert is_error is False, f"like_community_target 失败: {text[:200]}"
        assert '"liked":true' in text and '"changed":true' in text, f"点赞结果不符: {text[:300]}"
        com_paths = [p for p in MockGateway.paths if "/api/community/agent/" in p]
        assert len(com_paths) == 2, f"社区工具网关命中次数不符: {MockGateway.paths}"
        print("6) 社区工具族（帖子列表/显式点赞）经网关走通并携带 Bearer 头")

        # 第 9 次：今日计划走通并投影瘦身（含状态文案）。
        is_error, text, _ = infos[8]
        assert is_error is False, f"get_today_plan 失败: {text[:200]}"
        assert '"title":"题1"' in text and '"status_text":"进行中"' in text, f"今日计划投影不符: {text[:300]}"
        # 第 10 次：批量创建计划走通。
        is_error, text, _ = infos[9]
        assert is_error is False, f"create_study_plan 失败: {text[:200]}"
        assert '"created":1' in text and '"skippedQuestionIds":[]' in text, f"创建计划结果不符: {text[:300]}"
        prog_paths = [p for p in MockGateway.paths if "/api/review/agent/progress" in p]
        assert len(prog_paths) == 2, f"计划工具网关命中次数不符: {MockGateway.paths}"
        print("7) 计划工具族（今日计划/批量创建）经网关走通并携带 Bearer 头")

        # 第 11 次：笔记文件夹列表走通并投影瘦身。
        is_error, text, _ = infos[10]
        assert is_error is False, f"list_note_folders 失败: {text[:200]}"
        assert '"folder_name":"算法"' in text and '"note_count":2' in text, f"文件夹投影不符: {text[:300]}"
        # 第 12 次：新建笔记走通并返回全文。
        is_error, text, _ = infos[11]
        assert is_error is False, f"create_note 失败: {text[:200]}"
        assert '"noteId":1' in text and '"content":"# 题1\\n正文"' in text, f"新建笔记结果不符: {text[:300]}"
        note_paths = [p for p in MockGateway.paths if "/api/review/agent/note" in p]
        assert len(note_paths) == 2, f"笔记工具网关命中次数不符: {MockGateway.paths}"
        print("8) 笔记工具族（文件夹列表/新建笔记）经网关走通并携带 Bearer 头")

        assert result.final_response == "web 工具冒烟完成", f"最终回答不符: {result.final_response!r}"
        harness.close()

        print("\nSMOKE WEB OK: 工具清单、SSRF 守卫、公网抓取、代码执行、收藏夹/社区/计划/笔记工具全部通过")
        return 0
    finally:
        gw_server.shutdown()
        model_server.shutdown()


if __name__ == "__main__":
    raise SystemExit(main())
