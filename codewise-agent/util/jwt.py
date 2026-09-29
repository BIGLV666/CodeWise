import logging
import os

import jwt
from dotenv import load_dotenv
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from util.error_codes import AgentErrorCode

bearer = HTTPBearer(auto_error=False)
load_dotenv()

logger = logging.getLogger(__name__)


def get_java_jwt_algorithm(jwt_secret: str) -> str:
    """Match JJWT's signWith(key) algorithm selection by HMAC key length."""

    key_length = len(jwt_secret.encode("utf-8"))
    if key_length >= 64:
        return "HS512"
    if key_length >= 48:
        return "HS384"
    if key_length >= 32:
        return "HS256"
    raise RuntimeError("JWT_SECRET 至少需要 32 字节")


def get_token_from_header(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> str:
    if credentials is None:
        raise HTTPException(
            status_code=401, detail=AgentErrorCode.MISSING_TOKEN.message)
    return credentials.credentials


def get_current_user_id(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> int:
    if credentials is None:
        raise HTTPException(
            status_code=401, detail=AgentErrorCode.MISSING_TOKEN.message)
    try:
        jwt_secret = os.getenv("JWT_SECRET")
        if not jwt_secret:
            raise RuntimeError("JWT_SECRET 未配置")
        jwt_algorithm = get_java_jwt_algorithm(jwt_secret)
        claims = jwt.decode(
            credentials.credentials,
            jwt_secret,
            algorithms=[jwt_algorithm],
        )
        return int(claims["sub"])
    except RuntimeError as exc:
        # JWT_SECRET 缺失/长度不足属于服务端配置故障，不能伪装成用户登录态失效
        logger.error("服务端 JWT 配置异常: %s", exc)
        raise HTTPException(
            status_code=500, detail=AgentErrorCode.INTERNAL_ERROR.message) from exc
    except Exception as exc:
        # 解码细节全部只进日志，响应统一为登录态失效
        logger.warning("Token 校验失败: %s", exc)
        raise HTTPException(
            status_code=401, detail=AgentErrorCode.TOKEN_INVALID.message) from exc
