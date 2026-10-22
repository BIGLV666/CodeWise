"""服务端调用：默认经 SSH 在服务器本地用 curl 打 service-question（8084）。

为什么不用网关：网关要 JWT，而批量导入是运维动作；服务端服务之间靠内部 Token 认证，
从服务器本机直连 8084 带上 X-Internal-Token / X-User-Id 即可，也省掉了对外开端口。
"""

from __future__ import annotations

import json
import shlex
import subprocess
import urllib.error
import urllib.request
from typing import Optional


class ClientError(RuntimeError):
    pass


class ServerClient:
    def __init__(
        self,
        ssh: Optional[str] = None,
        direct_url: Optional[str] = None,
        internal_token: Optional[str] = None,
        admin_user_id: Optional[int] = None,
        remote_env: str = "/root/codewise/CodeWise/deploy/.env",
        mysql_container: str = "codewise-mysql-1",
        timeout: int = 900,
        max_batch_bytes: int = 4_000_000,
    ) -> None:
        if not ssh and not direct_url:
            raise ClientError("必须指定 --ssh 或 --direct-url")
        self.ssh = ssh
        self.direct_url = (direct_url or "").rstrip("/")
        self.internal_token = internal_token
        self.admin_user_id = admin_user_id
        self.remote_env = remote_env
        self.mysql_container = mysql_container
        self.timeout = timeout
        self.max_batch_bytes = max_batch_bytes

    # ---------- 初始化 ----------

    def bootstrap(self) -> None:
        if self.internal_token is None:
            self.internal_token = self._read_remote_env("CODEWISE_INTERNAL_TOKEN")
        if not self.internal_token:
            raise ClientError("未能获取内部 Token，请用 --internal-token 显式提供")
        if self.admin_user_id is None:
            self.admin_user_id = self._detect_admin_user_id()
        if not self.admin_user_id:
            raise ClientError("未能确定管理员用户 ID，请用 --admin-user-id 显式提供")

    def _read_remote_env(self, key: str) -> Optional[str]:
        if not self.ssh:
            raise ClientError("--direct-url 模式下必须显式提供 --internal-token")
        command = f"grep -m1 '^{key}=' {shlex.quote(self.remote_env)} | cut -d= -f2-"
        output = self._ssh(command)
        return output.strip() or None

    def _detect_admin_user_id(self) -> Optional[int]:
        if not self.ssh:
            raise ClientError("--direct-url 模式下必须显式提供 --admin-user-id")
        password = self._read_remote_env("MYSQL_ROOT_PASSWORD")
        if not password:
            return None
        sql = "select user_id from codewise_user.user where role_id in (0,2) order by role_id limit 1"
        command = (
            f"docker exec -e MYSQL_PWD={shlex.quote(password)} {shlex.quote(self.mysql_container)} "
            f"mysql -uroot -N -e {shlex.quote(sql)} 2>/dev/null"
        )
        output = self._ssh(command)
        values = [line.strip() for line in output.splitlines() if line.strip().isdigit()]
        return int(values[0]) if values else None

    # ---------- HTTP ----------

    def post(self, path: str, payload=None, timeout: Optional[int] = None) -> dict:
        return self._request("POST", path, payload, timeout)

    def get(self, path: str, params: Optional[dict] = None, timeout: Optional[int] = None) -> dict:
        if params:
            query = "&".join(
                f"{key}={urllib.parse.quote(str(value))}" for key, value in params.items()
            )
            path = f"{path}?{query}"
        return self._request("GET", path, None, timeout)

    def _request(self, method: str, path: str, payload, timeout: Optional[int]) -> dict:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
        if body is not None and len(body) > self.max_batch_bytes:
            raise ClientError(f"请求体过大（{len(body)} 字节），请调小 --batch-size")
        text = self._remote_request(method, path, body, timeout or self.timeout)
        try:
            response = json.loads(text)
        except json.JSONDecodeError as error:
            raise ClientError(f"服务端返回无法解析：{text[:400]}") from error
        code = response.get("code")
        if code != 200:
            raise ClientError(f"服务端返回错误（code={code}）：{response.get('message')}")
        return response.get("data")

    def _remote_request(self, method: str, path: str, body: Optional[bytes], timeout: int) -> str:
        url = f"{self.direct_url}{path}" if self.direct_url else f"http://127.0.0.1:8084{path}"
        if self.direct_url:
            return self._local_request(method, url, body, timeout)
        command = (
            f"curl -sS --max-time {timeout} -X {method} "
            f"-H {shlex.quote('Content-Type: application/json')} "
            f"-H {shlex.quote('X-Internal-Token: ' + self.internal_token)} "
            f"-H {shlex.quote('X-User-Id: ' + str(self.admin_user_id))} "
            + ("--data-binary @- " if body is not None else "")
            + shlex.quote(url)
        )
        return self._ssh(command, stdin=body)

    def _local_request(self, method: str, url: str, body: Optional[bytes], timeout: int) -> str:
        request = urllib.request.Request(url, data=body, method=method)
        request.add_header("Content-Type", "application/json")
        request.add_header("X-Internal-Token", self.internal_token or "")
        request.add_header("X-User-Id", str(self.admin_user_id))
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            return error.read().decode("utf-8", errors="replace")

    def _ssh(self, command: str, stdin: Optional[bytes] = None) -> str:
        result = subprocess.run(
            ["ssh", "-o", "BatchMode=yes", self.ssh, command],
            input=stdin,
            capture_output=True,
            timeout=self.timeout,
        )
        if result.returncode != 0:
            detail = (result.stderr or b"").decode("utf-8", errors="replace").strip()
            raise ClientError(f"SSH 执行失败：{detail[:300]}")
        return result.stdout.decode("utf-8", errors="replace")
