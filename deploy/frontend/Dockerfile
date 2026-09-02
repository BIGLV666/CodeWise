# 前端构建 + Nginx 托管。构建上下文为前端仓库目录：
#   docker build -t codewise/frontend:latest ../../CodeWise-frontend/CodeWise-frontend
# 注意：nginx 站点配置 deploy/nginx.conf 由 docker-compose 挂载覆盖，
# 单独运行本镜像时只提供静态文件，不带 API 反代。
# 前端仓库使用 pnpm（pnpm-lock.yaml），corepack 随 node:20 自带。
FROM node:22-alpine AS build
WORKDIR /fe
# Agent 接口基址：compose 构建注入 /agentapi（nginx 反代 codewise-agent:8000）
ARG VITE_AGENT_BASE_URL=/agentapi
ENV VITE_AGENT_BASE_URL=$$VITE_AGENT_BASE_URL
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
ENV CI=true \
    npm_config_ignore_scripts=true
RUN pnpm install --frozen-lockfile --ignore-scripts
COPY . .
RUN pnpm run build

FROM nginx:1.27-alpine
COPY --from=build /fe/dist /usr/share/nginx/html
EXPOSE 80
