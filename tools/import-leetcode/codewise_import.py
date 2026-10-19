"""命令行入口：扫描 → 本地对拍闸门 → 批量导入 → 轮询 → 报告。

示例：
    python3 codewise_import.py scan  --repo ~/project/leetcode --out manifest.json
    python3 codewise_import.py check --manifest manifest.json --out gate-report.json
    python3 codewise_import.py import --manifest manifest.json --gate-report gate-report.json \
        --ssh root@1.2.3.4 --limit 5 --batch-size 5
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass
from typing import Dict, List, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from importer import gate as gate_module  # noqa: E402
from importer import repo as repo_module  # noqa: E402
from importer.client import ClientError, ServerClient  # noqa: E402

MAX_REFERENCE_CHARS = 20000
MAX_NOTES_CHARS = 20000


@dataclass
class ImportOutcome:
    slug: str
    title: str
    question_id: Optional[int] = None
    generation_task_id: Optional[str] = None
    status: str = "PENDING"
    samples: int = 0
    error: Optional[str] = None


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="把本地仓库题目批量导入 CodeWise")
    subparsers = parser.add_subparsers(dest="command", required=True)

    scan_parser = subparsers.add_parser("scan", help="扫描仓库生成清单")
    scan_parser.add_argument("--repo", required=True, help="仓库根目录")
    scan_parser.add_argument("--out", default="manifest.json")
    scan_parser.add_argument("--include-unsupported", action="store_true", help="清单里保留类型不支持的题")

    check_parser = subparsers.add_parser("check", help="本地对拍闸门：编译并运行题目自带的 Test")
    check_parser.add_argument("--manifest", default="manifest.json")
    check_parser.add_argument("--out", default="gate-report.json")
    check_parser.add_argument("--repo", default=None, help="覆盖清单里的仓库根目录")
    check_parser.add_argument("--limit", type=int, default=0, help="只检查前 N 道（0 表示全部）")
    check_parser.add_argument("--timeout", type=int, default=600, help="单题编译/运行超时（秒）")
    check_parser.add_argument("--java-release", default="17", help="判题镜像的 Java 版本（默认 17，与 codewise-java-judge 一致）")

    import_parser = subparsers.add_parser("import", help="批量导入（服务端异步建题 + 生成隐藏用例）")
    import_parser.add_argument("--manifest", default="manifest.json")
    import_parser.add_argument("--gate-report", default=None, help="只导入闸门通过的题")
    import_parser.add_argument("--report", default="report.json")
    import_parser.add_argument("--ssh", default=None, help="如 root@1.2.3.4")
    import_parser.add_argument("--direct-url", default=None, help="直连服务地址，如 http://127.0.0.1:8084")
    import_parser.add_argument("--internal-token", default=None)
    import_parser.add_argument("--admin-user-id", type=int, default=None)
    import_parser.add_argument("--remote-env", default="/root/codewise/CodeWise/deploy/.env")
    import_parser.add_argument("--batch-size", type=int, default=5)
    import_parser.add_argument("--case-count", type=int, default=20)
    import_parser.add_argument("--seed", type=int, default=None)
    import_parser.add_argument("--limit", type=int, default=0)
    import_parser.add_argument("--only", default=None, help="只导入指定 slug，逗号分隔")
    import_parser.add_argument("--no-ai-description", action="store_true", help="不调用 AI 重写题面")
    import_parser.add_argument(
        "--descriptions",
        default=None,
        help="题面 JSON（{\"slug或标题\": \"题面正文\"}）；命中的题不再调 AI",
    )
    import_parser.add_argument(
        "--description-template",
        default=None,
        help="兜底题面模板，可用 {title}/{method} 占位（仅在没有题面的题上生效）",
    )
    import_parser.add_argument("--sample-count", type=int, default=1, help="回填为公开样例的用例条数")
    import_parser.add_argument("--poll-interval", type=int, default=5)
    import_parser.add_argument("--poll-timeout", type=int, default=900, help="单个生成任务等待上限（秒）")
    import_parser.add_argument("--dry-run", action="store_true")
    import_parser.add_argument("--resume", action="store_true", help="跳过报告里已成功的题")

    status_parser = subparsers.add_parser("status", help="查询批量导入任务状态")
    status_parser.add_argument("--task-id", required=True)
    status_parser.add_argument("--ssh", default=None)
    status_parser.add_argument("--direct-url", default=None)
    status_parser.add_argument("--internal-token", default=None)
    status_parser.add_argument("--admin-user-id", type=int, default=None)

    args = parser.parse_args(argv)
    if args.command == "scan":
        return command_scan(args)
    if args.command == "check":
        return command_check(args)
    if args.command == "import":
        return command_import(args)
    if args.command == "status":
        return command_status(args)
    return 1


# --------------------------------------------------------------------------- scan


def command_scan(args) -> int:
    repo_root = os.path.abspath(os.path.expanduser(args.repo))
    candidates = repo_module.scan(repo_root)
    payload = {
        "repo": repo_root,
        "scanned": len(candidates),
        "supported": sum(1 for item in candidates if item.supported),
        "candidates": [item.to_json() for item in candidates],
    }
    write_json(args.out, payload)

    supported = [item for item in candidates if item.supported]
    print(f"扫描完成：{len(candidates)} 道含 class Solution，其中 {len(supported)} 道类型受支持")
    unsupported = [item for item in candidates if not item.supported]
    if unsupported:
        print(f"类型不受支持（{len(unsupported)} 道）：")
        for item in unsupported[:10]:
            print(f"  - {item.directory}: {item.unsupported_types or item.skip_reason}")
        if len(unsupported) > 10:
            print(f"  ... 其余 {len(unsupported) - 10} 道见 {args.out}")
    print(f"清单已写入 {args.out}")
    return 0


# -------------------------------------------------------------------------- check


def command_check(args) -> int:
    manifest = read_json(args.manifest)
    repo_root = os.path.abspath(os.path.expanduser(args.repo or manifest["repo"]))
    candidates = [item for item in manifest["candidates"] if item.get("supported")]
    if args.limit:
        candidates = candidates[: args.limit]

    results: List[Dict] = []
    passed = 0
    for index, item in enumerate(candidates, start=1):
        outcome = gate_module.run_gate(
            repo_root,
            item["java_file"],
            item.get("test_file"),
            timeout=args.timeout,
        )
        if outcome.ok:
            # 本地对拍通过还不够：判题容器是 JDK 17，本地常用 JDK 21 的 API，
            # 这类解法在沙箱里会编译失败，必须先按判题版本编一遍
            with open(os.path.join(repo_root, item["java_file"]), "r", encoding="utf-8") as handle:
                cleaned = repo_module.clean_solution(handle.read())
            compile_result = gate_module.check_judge_compilation(
                cleaned,
                [param_type for param_type, _ in item["parameters"]],
                item["return_type"],
                release=args.java_release,
            )
            if not compile_result.ok:
                outcome = compile_result
        if outcome.ok:
            passed += 1
        results.append(
            {
                "slug": item["slug"],
                "directory": item["directory"],
                "ok": outcome.ok,
                "skipped": outcome.skipped,
                "message": outcome.message,
            }
        )
        flag = "OK " if outcome.ok else "FAIL"
        print(f"[{index}/{len(candidates)}] {flag} {item['directory']} {outcome.message[:120]}")

    write_json(args.out, {"repo": repo_root, "total": len(results), "passed": passed, "results": results})
    print(f"对拍闸门：{passed}/{len(results)} 通过，报告写入 {args.out}")
    return 0 if passed == len(results) else 1


# ------------------------------------------------------------------------- import


def command_import(args) -> int:
    manifest = read_json(args.manifest)
    repo_root = os.path.abspath(os.path.expanduser(manifest["repo"]))
    candidates = {item["slug"]: item for item in manifest["candidates"] if item.get("supported")}
    descriptions = read_json(args.descriptions) if args.descriptions else {}

    if args.gate_report:
        gate_report = read_json(args.gate_report)
        allowed = {row["slug"] for row in gate_report["results"] if row["ok"]}
        candidates = {slug: item for slug, item in candidates.items() if slug in allowed}

    if args.only:
        wanted = {value.strip() for value in args.only.split(",") if value.strip()}
        candidates = {slug: item for slug, item in candidates.items() if slug in wanted}

    selected = sorted(candidates.values(), key=lambda item: item["directory"].count("/"))
    selected = [item for item in selected if item.get("method_name") and item.get("parameters") is not None]
    if args.limit:
        selected = selected[: args.limit]
    if not selected:
        print("没有可导入的题目（检查 --only / --gate-report / 清单）")
        return 1

    if args.dry_run:
        for item in selected:
            payload = build_item(repo_root, item, args, descriptions)
            print(
                f"[dry-run] {item['directory']} -> {item['method_name']} "
                f"参数={payload['parameterConfig']} 解法={len(payload['solutionCode'])}B "
                f"参考={len(payload.get('referenceCode') or '')}B 笔记={len(payload.get('notes') or '')}B"
            )
        return 0

    client = ServerClient(
        ssh=args.ssh,
        direct_url=args.direct_url,
        internal_token=args.internal_token,
        admin_user_id=args.admin_user_id,
        remote_env=args.remote_env,
    )
    client.bootstrap()
    print(f"已连接（管理员 ID={client.admin_user_id}），准备导入 {len(selected)} 道题")

    previous = read_json(args.report) if (args.resume and os.path.exists(args.report)) else None
    done = {row["slug"] for row in previous["items"] if row.get("status") == "SUCCESS"} if previous else set()
    outcomes: List[ImportOutcome] = []
    if previous:
        outcomes = [ImportOutcome(**row) for row in previous["items"] if row.get("status") != "SUCCESS"]

    pending = [item for item in selected if item["slug"] not in done]
    for chunk_start in range(0, len(pending), args.batch_size):
        chunk = pending[chunk_start: chunk_start + args.batch_size]
        outcomes.extend(import_chunk(client, repo_root, chunk, args, descriptions))
        write_report(args.report, outcomes)

    write_report(args.report, outcomes)
    succeeded = sum(1 for row in outcomes if row.status == "SUCCESS")
    print(f"导入结束：成功 {succeeded}，失败 {len(outcomes) - succeeded}，报告写入 {args.report}")
    return 0


def import_chunk(client: ServerClient, repo_root: str, chunk: List[Dict], args, descriptions: Dict) -> List[ImportOutcome]:
    payload = {
        "items": [build_item(repo_root, item, args, descriptions) for item in chunk],
        "caseCount": args.case_count,
        "allowDescriptionGeneration": not args.no_ai_description,
    }
    if args.seed is not None:
        payload["seed"] = args.seed

    outcomes = {item["slug"]: ImportOutcome(slug=item["slug"], title=item.get("title") or item["directory"])
                for item in chunk}
    for item in chunk:
        print(f"提交 {item['directory']} …")

    task = client.post("/api/question/function/batch-import", payload)
    task_id = task["taskId"]
    print(f"批量任务 {task_id} 已创建，处理 {len(chunk)} 道题")

    task = wait_batch_task(client, task_id, args)
    states = {row["title"]: row for row in task["items"]}
    for item in chunk:
        outcome = outcomes[item["slug"]]
        state = states.get(item.get("title") or item["directory"]) or {}
        if state.get("status") == "FAILED" or not state.get("questionId"):
            outcome.status = "FAILED"
            outcome.error = state.get("errorMessage") or "建题阶段未返回题目 ID"
            print(f"  ✗ {item['directory']}：{outcome.error}")
            continue
        outcome.question_id = state["questionId"]
        outcome.generation_task_id = state.get("generationTaskId")
        outcome = finish_generation(client, outcome, item, args, task)
        outcomes[item["slug"]] = outcome
    return list(outcomes.values())


def finish_generation(client: ServerClient, outcome: ImportOutcome, item: Dict, args, batch_task) -> ImportOutcome:
    if not outcome.generation_task_id:
        outcome.status = "FAILED"
        outcome.error = "未拿到隐藏测试用例生成任务 ID"
        return outcome

    deadline = time.time() + args.poll_timeout
    while True:
        generation = client.get(
            "/api/question/function/test-cases/generate/status",
            {"taskId": outcome.generation_task_id},
        )
        status = generation.get("status")
        if status == "SUCCESS":
            outcome.status = "SUCCESS"
            break
        if status == "FAILED":
            outcome.status = "FAILED"
            outcome.error = generation.get("errorMessage") or "隐藏测试用例生成失败"
            break
        if time.time() > deadline:
            outcome.status = "TIMEOUT"
            outcome.error = f"等待生成超过 {args.poll_timeout} 秒"
            break
        time.sleep(args.poll_interval)

    if outcome.status == "SUCCESS" and args.sample_count > 0:
        try:
            # publish=true：题目建时为私密，用例生成成功后才上架，避免出现 0 测试点的公开题
            promoted = client.post(
                f"/api/question/function/test-cases/sample/{outcome.question_id}"
                f"?count={args.sample_count}&publish=true",
                None,
                timeout=120,
            )
            outcome.samples = promoted or 0
        except ClientError as error:
            # 样例回填/上架失败不影响题目与隐藏用例，报告里留痕即可
            outcome.error = f"样例回填或上架失败：{error}"

    flag = "✓" if outcome.status == "SUCCESS" else "✗"
    print(f"  {flag} {item['directory']} questionId={outcome.question_id} 状态={outcome.status} "
          f"样例={outcome.samples} {outcome.error or ''}")
    return outcome


def wait_batch_task(client: ServerClient, task_id: str, args) -> Dict:
    deadline = time.time() + max(args.poll_timeout, 120)
    while True:
        task = client.get("/api/question/function/batch-import/status", {"taskId": task_id})
        if task.get("status") != "RUNNING":
            return task
        if time.time() > deadline:
            raise ClientError(f"批量任务 {task_id} 超时未完成")
        time.sleep(args.poll_interval)


def build_item(repo_root: str, candidate: Dict, args, descriptions: Optional[Dict] = None) -> Dict:
    solution_path = os.path.join(repo_root, candidate["java_file"])
    with open(solution_path, "r", encoding="utf-8") as handle:
        solution_code = repo_module.clean_solution(handle.read())

    reference_code = None
    if candidate.get("test_file"):
        reference_code = read_text(os.path.join(repo_root, candidate["test_file"]))[:MAX_REFERENCE_CHARS]

    title = candidate.get("title") or candidate["directory"]
    item = {
        "title": title,
        "methodName": candidate["method_name"],
        "className": "Solution",
        "parameterConfig": json.dumps(
            [{"type": t, "name": n} for t, n in candidate["parameters"]], ensure_ascii=False
        ),
        "outputType": candidate["return_type"],
        "solutionCode": solution_code,
    }
    prepared = resolve_description(candidate, title, args, descriptions or {})
    if prepared:
        # 自带题面时不再调用 AI，直接落这个文本
        item["description"] = prepared
    if candidate.get("difficulty") is not None:
        item["difficulty"] = candidate["difficulty"]
    if candidate.get("tags"):
        item["tags"] = candidate["tags"]
    if candidate.get("notes_digest"):
        item["notes"] = candidate["notes_digest"][:MAX_NOTES_CHARS]
    if reference_code:
        item["referenceCode"] = reference_code
    return item


# ------------------------------------------------------------------------- status


def command_status(args) -> int:
    client = ServerClient(
        ssh=args.ssh,
        direct_url=args.direct_url,
        internal_token=args.internal_token,
        admin_user_id=args.admin_user_id,
    )
    client.bootstrap()
    task = client.get("/api/question/function/batch-import/status", {"taskId": args.task_id})
    print(json.dumps(task, ensure_ascii=False, indent=2))
    return 0


# -------------------------------------------------------------------------- utils


def resolve_description(candidate: Dict, title: str, args, descriptions: Dict) -> Optional[str]:
    """题面优先级：外部题面 JSON（按 slug 或标题命中）> 兜底模板 > 交给 AI。"""

    for key in (candidate["slug"], title, candidate["directory"]):
        value = descriptions.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    template = getattr(args, "description_template", None)
    if template:
        return template.replace("{title}", title).replace("{method}", candidate["method_name"])
    return None


def write_report(path: str, outcomes: List[ImportOutcome]) -> None:
    succeeded = sum(1 for row in outcomes if row.status == "SUCCESS")
    write_json(
        path,
        {
            "generatedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
            "summary": {"total": len(outcomes), "success": succeeded, "failed": len(outcomes) - succeeded},
            "items": [row.__dict__ for row in outcomes],
        },
    )


def read_text(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return ""


def read_json(path: str) -> Dict:
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: str, payload: Dict) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    sys.exit(main())
