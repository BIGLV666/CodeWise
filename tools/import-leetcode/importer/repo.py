"""仓库扫描：把 ~/project/leetcode 里的题目目录整理成可导入的清单。

只做静态分析，不接触任何第三方题面：题名与难度取自仓库自带的解法注释/思路笔记，
题面正文由服务端按解法源码原创重写。
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional

# 判题侧支持作为函数参数的 Java 类型（service-judge CodeBuild.getType）
SUPPORTED_PARAM_TYPES = {
    "int",
    "long",
    "String",
    "int[]",
    "String[]",
    "int[][]",
    "ListNode",
    "TreeNode",
}

# 非题目目录：共享库、学习专题、其他平台题单与构建产物
EXCLUDED_DIRS = {
    ".git",
    "out",
    "target",
    "common",
    "datastructure",
    "sort",
    "cpp",
    "java",
    "python",
    "luogu",
    "lanqiao",
    "牛客",
    "秦皇岛2025ccpc",
    "leetcode",  # 共享的 ListNode/TreeNode/Codec 工具
}

CLASS_SOLUTION = re.compile(r"(?m)^\s*(?:public\s+)?class\s+Solution\b")
METHOD_PATTERN = re.compile(
    r"(?m)^\s*(?:(?:public|protected|private)\s+)?"
    r"(?:(?:static|final|synchronized|native|abstract)\s+)*"
    r"([\w<>\[\], ?.]+?)\s+(\w+)\s*\(([^)]*)\)\s*\{"
)
PACKAGE_PATTERN = re.compile(r"(?m)^\s*package\s+[\w.]+\s*;\s*$")
JAVADOC_URL = re.compile(
    r"https?://leetcode\.cn/problems/([A-Za-z0-9\-]+)/[^>]*>\s*([^<]+?)\s*</a>"
)
JAVADOC_TITLE = re.compile(r"(?:LeetCode|力扣)\s*([0-9]+)\.\s*([^\s(（]+)")
NOTES_HEADING = re.compile(r"^#\s*(?:([0-9]+)\.\s*)?(.+?)\s*(?:[（(](Easy|Medium|Hard)[）)])?\s*$")
DIFFICULTY_BY_NAME = {"easy": 1, "medium": 2, "hard": 3}
DIFFICULTY_BY_CN = {"简单": 1, "中等": 2, "困难": 3}

# 只删仓库内部的 import（判题沙箱里没有这些包，保留会编译失败）
LOCAL_IMPORT_PATTERN = re.compile(
    r"(?m)^\s*import\s+(?:leetcode|common|datastructure|Hot100|hot100)[\w.]*\s*;\s*$"
)


@dataclass
class Candidate:
    """一个候选题目的静态信息。"""

    directory: str
    java_file: str
    test_file: Optional[str] = None
    notes_file: Optional[str] = None
    class_name: str = "Solution"
    method_name: str = ""
    return_type: str = ""
    parameters: List[List[str]] = field(default_factory=list)
    title: str = ""
    difficulty: Optional[int] = None
    tags: Optional[str] = None
    notes_digest: Optional[str] = None
    unsupported_types: List[str] = field(default_factory=list)
    skip_reason: Optional[str] = None

    @property
    def slug(self) -> str:
        return self.directory.replace("/", "-")

    @property
    def supported(self) -> bool:
        return not self.unsupported_types and not self.skip_reason

    def parameter_config(self) -> str:
        return json.dumps(
            [{"type": t, "name": n} for t, n in self.parameters],
            ensure_ascii=False,
        )

    def to_json(self) -> dict:
        data = asdict(self)
        data["slug"] = self.slug
        data["supported"] = self.supported
        return data


def scan(repo_root: str) -> List[Candidate]:
    """遍历仓库，返回所有含 class Solution 的候选题（含不受支持的，便于报告）。"""

    difficulty_by_title = _load_difficulty_index(repo_root)
    candidates: List[Candidate] = []
    for directory, dirnames, filenames in os.walk(repo_root):
        dirnames[:] = [
            name for name in dirnames if name not in EXCLUDED_DIRS and not name.startswith(".")
        ]
        rel_dir = os.path.relpath(directory, repo_root)
        if rel_dir == ".":
            continue
        java_files = [f for f in filenames if f.endswith(".java")]
        solution_files = [f for f in java_files if f.lower() != "test.java"]
        if not solution_files:
            continue

        candidate = None
        for solution_name in sorted(solution_files):
            source = _read(os.path.join(directory, solution_name))
            if not CLASS_SOLUTION.search(source):
                continue
            parsed = _parse_signature(source)
            if parsed is None:
                continue
            method_name, return_type, parameters = parsed
            candidate = Candidate(
                directory=rel_dir if rel_dir != "." else ".",
                java_file=os.path.join(rel_dir, solution_name),
                test_file=_first_existing(directory, rel_dir, ["Test.java"]),
                notes_file=_first_existing(directory, rel_dir, ["思路.md", "笔记.md"]),
                method_name=method_name,
                return_type=return_type,
                parameters=parameters,
                unsupported_types=sorted(
                    {t for t, _ in parameters if t not in SUPPORTED_PARAM_TYPES}
                ),
            )
            candidate.title, candidate.difficulty = _derive_title_and_difficulty(
                source, directory, candidate
            )
            candidate.tags = _derive_tags(candidate.notes_file, directory)
            candidate.notes_digest = _notes_digest(candidate.notes_file, directory)
            if candidate.difficulty is None:
                candidate.difficulty = difficulty_by_title.get(_title_key(candidate.title))
            break

        if candidate is not None:
            candidates.append(candidate)

    candidates.sort(key=lambda item: (item.directory.count("/"), item.directory))
    return candidates


def _first_existing(directory: str, rel_dir: str, names: List[str]) -> Optional[str]:
    for name in names:
        path = os.path.join(directory, name)
        if os.path.isfile(path):
            return os.path.join(rel_dir, name)
    return None


def _parse_signature(source: str):
    """取 class Solution 里的第一个公有方法作为题目函数签名。"""

    match = CLASS_SOLUTION.search(source)
    if match is None:
        return None
    body = source[match.end():]
    for method in METHOD_PATTERN.finditer(body):
        return_type = _normalize_type(method.group(1))
        name = method.group(2)
        if name in {"main", "Solution"} or not return_type:
            continue
        parameters = []
        raw_parameters = method.group(3).strip()
        if raw_parameters:
            for raw in _split_parameters(raw_parameters):
                parsed = _parse_parameter(raw)
                if parsed is None:
                    return None
                parameters.append(list(parsed))
        return name, return_type, parameters
    return None


def _split_parameters(raw: str) -> List[str]:
    parts, depth, current = [], 0, ""
    for char in raw:
        if char in "<([":
            depth += 1
        elif char in ">)]":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += char
    if current.strip():
        parts.append(current)
    return parts


def _parse_parameter(raw: str):
    tokens = [token for token in raw.replace("\t", " ").split(" ") if token]
    tokens = [token for token in tokens if token not in {"final", "static"}]
    if len(tokens) < 2:
        return None
    name = tokens[-1]
    param_type = _normalize_type(" ".join(tokens[:-1]))
    if not param_type or not name:
        return None
    return param_type, name


def _normalize_type(raw: str) -> str:
    value = raw.strip()
    value = re.sub(r"\s+", "", value)
    return value.replace("[]", "[]")


def _derive_title_and_difficulty(source: str, directory: str, candidate: Candidate):
    """题名与难度：优先解法注释里的链接标题，其次思路笔记的标题，最后退回目录名。"""

    match = JAVADOC_URL.search(source)
    if match:
        return _clean_title(match.group(2)), _difficulty_from_text(source)

    match = JAVADOC_TITLE.search(source)
    if match:
        return _clean_title(match.group(2)), _difficulty_from_text(source)

    if candidate.notes_file:
        notes = _read(os.path.join(directory, candidate.notes_file))
        for line in notes.splitlines():
            heading = NOTES_HEADING.match(line.strip())
            if heading and heading.group(2):
                difficulty = DIFFICULTY_BY_NAME.get((heading.group(3) or "").lower())
                return _clean_title(heading.group(2)), difficulty
        difficulty = _difficulty_from_text(notes)
        if difficulty is not None:
            return os.path.basename(directory), difficulty

    return os.path.basename(directory), _difficulty_from_text(source)


def _difficulty_from_text(text: str) -> Optional[int]:
    match = re.search(r"[（(](Easy|Medium|Hard)[）)]", text)
    if match:
        return DIFFICULTY_BY_NAME[match.group(1).lower()]
    match = re.search(r"[（(](简单|中等|困难)[）)]", text)
    if match:
        return DIFFICULTY_BY_CN[match.group(1)]
    return None


def _clean_title(raw: str) -> str:
    value = raw.strip()
    value = re.sub(r"^[0-9]+\s*[.、]\s*", "", value)
    value = re.sub(r"[（(](Easy|Medium|Hard|简单|中等|困难)[）)]\s*$", "", value)
    return value.strip()


def _derive_tags(notes_file: Optional[str], directory: str) -> Optional[str]:
    if not notes_file:
        return None
    notes = _read(os.path.join(directory, notes_file))
    match = re.search(r"\*\*标签\*\*[:：]\s*(.+)", notes)
    if match:
        return match.group(1).strip()
    return None


def _notes_digest(notes_file: Optional[str], directory: str, limit: int = 20000) -> Optional[str]:
    if not notes_file:
        return None
    notes = _read(os.path.join(directory, notes_file))
    return notes[:limit] if notes else None


def clean_solution(source: str) -> str:
    """把仓库解法整理成判题沙箱可直接编译的形态。

    判题侧固定 new Solution()，且会自行补齐 ListNode/TreeNode 与 java.util.*，
    因此这里只需要去掉包声明与仓库内部 import（沙箱里不存在这些包）。
    """

    cleaned = PACKAGE_PATTERN.sub("", source)
    cleaned = LOCAL_IMPORT_PATTERN.sub("", cleaned)
    return cleaned.strip() + "\n"


def _title_key(title: str) -> str:
    return re.sub(r"\s+", "", title or "")


def _load_difficulty_index(repo_root: str) -> Dict[str, int]:
    """从 PROBLEMS.md 的索引表补全难度：题名是键，题号/链接不可靠，只有难度是需要的。"""

    index: Dict[str, int] = {}
    path = os.path.join(repo_root, "PROBLEMS.md")
    if not os.path.isfile(path):
        return index
    row_pattern = re.compile(r"^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*(Easy|Med|Medium|Hard)\b")
    for line in _read(path).splitlines():
        match = row_pattern.match(line.strip())
        if not match:
            continue
        name = match.group(2).strip()
        difficulty = {"easy": 1, "med": 2, "medium": 2, "hard": 3}[match.group(3).lower()]
        index[_title_key(_clean_title(name))] = difficulty
    return index


def _read(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return ""
