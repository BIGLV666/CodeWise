# syntax=docker/dockerfile:1
# 前端构建 + Nginx 托管。构建上下文为前端仓库目录：
#   docker build -t codewise/frontend:latest ../CodeWise-frontend/CodeWise-frontend
# 注意：nginx 站点配置 deploy/nginx.conf 由 docker-compose 挂载覆盖，
# 单独运行本镜像时只提供静态文件，不带 API 反代。
FROM node:20-alpine AS build
WORKDIR /fe
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /fe/dist /usr/share/nginx/html
EXPOSE 80
