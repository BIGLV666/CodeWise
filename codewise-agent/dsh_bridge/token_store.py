"""会话私有 Bearer token 文件管理。

工具在 dsh 运行时侧每次调用网关前读取 token 文件，因此门面可以在每轮对话
前刷新 token，避免长期驻留的运行时进程持有过期凭据，也不需要把 token 写进
进程环境变量（环境变量只在进程启动时注入一次，无法轮换）。

安全约束：
- 文件按会话隔离（一个会话一个文件），与其他用户的运行时进程不可见；
- 写入使用“临时文件 + 原子替换”，避免运行时读到半截内容；
- 目录与文件权限尽量收紧（POSIX 下 0o600；Windows 下依赖用户目录默认 ACL）。
"""

from __future__ import annotations

import os
from pathlib import Path

from dsh_bridge.settings import DshSettings


class TokenStore:
    """负责会话 token 文件的写入与清理。"""

    def __init__(self, settings: DshSettings) -> None:
        self._token_dir = settings.token_dir

    def token_path(self, conversation_id: int) -> Path:
        """返回指定会话的 token 文件路径。"""

        return self._token_dir / f"{conversation_id}.token"

    def write(self, conversation_id: int, token: str) -> Path:
        """原子写入指定会话的 Bearer token，返回文件路径。

        先写入同目录临时文件再 os.replace，保证运行时读到的始终是完整内容。
        token 不做任何日志记录。
        """

        if not token:
            raise ValueError("token 不能为空")
        self._token_dir.mkdir(parents=True, exist_ok=True)
        target = self.token_path(conversation_id)
        tmp = target.with_suffix(".token.tmp")
        tmp.write_text(token, encoding="utf-8")
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            # Windows 的 FAT/部分 ACL 场景下 chmod 可能失败，属可接受降级。
            pass
        os.replace(tmp, target)
        return target

    def cleanup(self, conversation_id: int) -> None:
        """删除指定会话的 token 文件（会话删除或运行时回收时调用）。"""

        try:
            self.token_path(conversation_id).unlink(missing_ok=True)
        except OSError:
            pass
