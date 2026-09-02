"""pytest 全局夹具。

必须在导入任何项目模块**之前**设置环境变量（entry.database、util 等在导入期
读取 env），因此这里统一在 collection 早期写 os.environ。

数据库使用临时 SQLite 文件（entry.database 的连接池不兼容内存库），
并在导入后建表。
"""

from __future__ import annotations

import base64
import os
import tempfile
from pathlib import Path

import pytest

# 项目根目录（tests/ 的上一级）
REPO_ROOT = Path(__file__).resolve().parents[1]

_TMP_DIR = tempfile.mkdtemp(prefix="codewise-agent-test-")
_DB_PATH = Path(_TMP_DIR) / "test.sqlite3"

# entry.database 在导入期读取；JWT_SECRET 需 >= 32 字节；主密钥需 32 字节 base64。
os.environ.setdefault("DATABASE_URL", f"sqlite+pysqlite:///{_DB_PATH.as_posix()}")
os.environ.setdefault("JWT_SECRET", "unit-test-jwt-secret-key-must-be-at-least-32-bytes!")
os.environ.setdefault("API_KEY_MASTER_KEY", base64.b64encode(b"0" * 32).decode("ascii"))
os.environ.setdefault("CODEWISE_GATEWAY_URL", "http://127.0.0.1:1")
os.environ.setdefault("CODEWISE_TOKEN_FILE", str(Path(_TMP_DIR) / "token.token"))

sys_path_added = str(REPO_ROOT)
if sys_path_added not in __import__("sys").path:
    __import__("sys").path.insert(0, sys_path_added)

# 环境就绪后再导入项目模块并建表；必须先导入全部模型让 Base 注册元数据。
import entry  # noqa: E402, F401
from entry.Conversrtion import AgentConversation  # noqa: E402, F401
from entry.AgentMessage import AgentMessage  # noqa: E402, F401
from entry.UserAiConfig import UserAiConfig  # noqa: E402, F401
from entry.database import Base, engine  # noqa: E402

Base.metadata.create_all(engine)

# SQLite 不为 BigInteger 主键生成自增 rowid；测试中用监听器补齐 agent_message 主键。
import itertools  # noqa: E402

from sqlalchemy import event  # noqa: E402

_message_ids = itertools.count(9001)


@event.listens_for(AgentMessage, "before_insert")
def _assign_agent_message_id(_mapper, _connection, target: AgentMessage) -> None:
    if target.agent_message_id is None:
        target.agent_message_id = next(_message_ids)


@pytest.fixture()
def db_session():
    """每个测试独立的 SQLAlchemy 会话，结束后回滚并关闭。"""

    from entry.database import SessionLocal

    session = SessionLocal()
    try:
        yield session
        session.rollback()
    finally:
        session.close()


@pytest.fixture()
def tmp_paths(tmp_path):
    """隔离的会话/凭据目录。"""

    return {"sessions": tmp_path / "sessions", "tokens": tmp_path / "tokens"}
