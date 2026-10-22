"""本地对拍闸门：导入前先在本机把题目自带的测试跑通。

仓库里的 Test.java 是题目自带的「官方样例 + 独立参考实现对拍 + 随机压测」，
跑通即代表解法已交叉验证过；没跑通的题目不进导入流程，避免把错解当标准答案。
"""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
from dataclasses import dataclass
from typing import List, Optional

PACKAGE_PATTERN = re.compile(r"(?m)^\s*package\s+([\w.]+)\s*;")
TOP_LEVEL_LIST_NODE = re.compile(r"(?m)^(?:public\s+)?class\s+ListNode\b")
TOP_LEVEL_TREE_NODE = re.compile(r"(?m)^(?:public\s+)?class\s+TreeNode\b")

LIST_NODE_CLASS = """
class ListNode {
    int val;
    ListNode next;
    ListNode(int val) { this.val = val; }
}
"""

TREE_NODE_CLASS = """
class TreeNode {
    int val;
    TreeNode left;
    TreeNode right;
    TreeNode(int val) { this.val = val; }
}
"""


@dataclass
class GateResult:
    ok: bool
    message: str
    skipped: bool = False


def run_gate(
    repo_root: str,
    java_file: str,
    test_file: Optional[str],
    javac: str = "javac",
    java: str = "java",
    timeout: int = 600,
) -> GateResult:
    """编译并运行题目自带的 Test，返回是否通过。"""

    if not test_file:
        return GateResult(True, "仓库未提供 Test.java，跳过本地对拍闸门", skipped=True)

    test_path = os.path.join(repo_root, test_file)
    if not os.path.isfile(test_path):
        return GateResult(True, "未找到测试文件，跳过本地对拍闸门", skipped=True)

    sources = _collect_sources(repo_root, java_file, test_file)
    test_class = _test_class_name(test_path)
    if test_class is None:
        return GateResult(False, "无法解析测试类的包名与类名，已跳过导入")

    with tempfile.TemporaryDirectory(prefix="codewise-gate-") as workspace:
        classes_dir = os.path.join(workspace, "classes")
        os.makedirs(classes_dir, exist_ok=True)
        compile_result = _run(
            [javac, "-encoding", "UTF-8", "-nowarn", "-sourcepath", repo_root,
             "-d", classes_dir, *sources],
            timeout=timeout,
        )
        if compile_result.returncode != 0:
            return GateResult(False, "编译失败：" + _tail(compile_result.stderr or compile_result.stdout))

        run_result = _run([java, "-cp", classes_dir, test_class], timeout=timeout)
        if run_result.returncode != 0:
            return GateResult(False, "本地对拍未通过：" + _tail(run_result.stdout or run_result.stderr))

    return GateResult(True, "本地对拍通过")


def judge_source(cleaned: str, parameter_types: List[str], return_type: str) -> str:
    """还原判题侧真正编译的源码（与 service-judge 的 CodeBuild.build 保持一致）。"""

    source = cleaned
    if "import java.util.*;" not in source:
        source = "import java.util.*;\n" + source
    if "import java.lang.*;" not in source:
        source = "import java.lang.*;\n" + source
    needs_list = "ListNode" in parameter_types or return_type == "ListNode"
    needs_tree = "TreeNode" in parameter_types or return_type == "TreeNode"
    if needs_list and not TOP_LEVEL_LIST_NODE.search(source):
        source += LIST_NODE_CLASS
    if needs_tree and not TOP_LEVEL_TREE_NODE.search(source):
        source += TREE_NODE_CLASS
    return source


def check_judge_compilation(
    cleaned: str,
    parameter_types: List[str],
    return_type: str,
    release: str = "17",
    javac: str = "javac",
    timeout: int = 120,
) -> GateResult:
    """按判题镜像的 JDK 版本编译解法。

    判题容器是 JDK 17，而本地仓库常用 JDK 21 的 API（如 List.removeLast）。
    这类解法在本地能跑、进了沙箱却编译失败，最终只会表现为「执行结果数量不完整」，
    所以在导入前先用相同版本编一遍，把问题挡在闸门里。
    """

    with tempfile.TemporaryDirectory(prefix="codewise-javac-") as workspace:
        source_path = os.path.join(workspace, "Solution.java")
        with open(source_path, "w", encoding="utf-8") as handle:
            handle.write(judge_source(cleaned, parameter_types, return_type))
        result = _run(
            [javac, "--release", release, "-encoding", "UTF-8", "-nowarn", "-d", workspace, source_path],
            timeout=timeout,
        )
        if result.returncode != 0:
            return GateResult(False, f"按 JDK {release} 编译失败：" + _tail(result.stderr or result.stdout))
    return GateResult(True, f"按 JDK {release} 编译通过")


def _collect_sources(repo_root: str, java_file: str, test_file: str) -> List[str]:
    """题目目录下全部 Java 源文件 + 仓库共享的 leetcode 工具类。"""

    sources = []
    problem_dir = os.path.dirname(os.path.join(repo_root, java_file))
    for name in sorted(os.listdir(problem_dir)):
        if name.endswith(".java"):
            sources.append(os.path.join(problem_dir, name))

    shared_dir = os.path.join(repo_root, "leetcode")
    if os.path.isdir(shared_dir):
        for name in sorted(os.listdir(shared_dir)):
            if name.endswith(".java") and name != "Test.java":
                sources.append(os.path.join(shared_dir, name))
    return sources


def _test_class_name(test_path: str) -> Optional[str]:
    try:
        with open(test_path, "r", encoding="utf-8") as handle:
            source = handle.read()
    except OSError:
        return None
    stem = os.path.splitext(os.path.basename(test_path))[0]
    match = PACKAGE_PATTERN.search(source)
    return f"{match.group(1)}.{stem}" if match else stem


def _run(command: List[str], timeout: int) -> subprocess.CompletedProcess:
    return subprocess.run(
        command,
        capture_output=True,
        text=True,
        errors="replace",
        timeout=timeout,
    )


def _tail(text: Optional[str], lines: int = 8) -> str:
    if not text:
        return "无输出"
    stripped = [line for line in text.strip().splitlines() if line.strip()]
    return " | ".join(stripped[-lines:])[:600]
