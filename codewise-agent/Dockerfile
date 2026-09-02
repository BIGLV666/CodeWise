# CodeWise Python Agent（LangGraph/dsh 桥接）镜像。
# 运行时同时需要 Python 3.11+（FastAPI 服务）与 Node >= 22.19（dsh 运行时子进程），
# 选 node:22-bookworm-slim 单镜像双运行时（Debian bookworm 自带 python3.11）。
# 构建上下文为本目录（codewise-agent/）：
#   docker build -t codewise/codewise-agent:latest codewise-agent/
FROM node:22-bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-pip curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 先装依赖再拷源码，充分利用构建缓存
COPY requirements.txt ./
RUN pip3 install --break-system-packages --no-cache-dir -r requirements.txt

COPY agent-runtime/package.json agent-runtime/package-lock.json ./agent-runtime/
RUN cd agent-runtime && npm ci

COPY . .

# 容器运行期需要写会话/token 文件（data/），镜像内置 uid 1000 的 node 用户
RUN mkdir -p /app/data && chown -R node:node /app
USER node

ENV AGENT_HOST=0.0.0.0
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=5 \
  CMD curl -fs http://127.0.0.1:8000/docs || exit 1

CMD ["python3", "main.py"]
