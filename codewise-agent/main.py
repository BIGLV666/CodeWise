import os

import uvicorn

from api.main import app


if __name__ == "__main__":
    # 容器部署必须监听 0.0.0.0（默认经环境变量注入），本地开发默认回环
    uvicorn.run(app, host=os.getenv("AGENT_HOST", "127.0.0.1"), port=int(os.getenv("AGENT_PORT", "8000")))
