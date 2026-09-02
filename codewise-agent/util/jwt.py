import os

import jwt
from dotenv import load_dotenv
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

bearer = HTTPBearer(auto_error=False)
load_dotenv()


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
        raise HTTPException(status_code=401, detail="请求未携带 Authorization Bearer Token")
    return credentials.credentials


def get_current_user_id(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> int:
    if credentials is None:
        raise HTTPException(status_code=401, detail="请求未携带 Authorization Bearer Token")
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
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    except jwt.ExpiredSignatureError as exc:
        raise HTTPException(status_code=401, detail="Token 已过期，请重新登录") from exc
    except jwt.InvalidSignatureError as exc:
        raise HTTPException(
            status_code=401,
            detail="Token 签名无效，请确认 Python JWT_SECRET 与 Java jwt.secret 一致",
        ) from exc
    except (jwt.PyJWTError, KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=401, detail="Token 格式无效") from exc
