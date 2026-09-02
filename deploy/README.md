# CodeWise Docker 快速开始（Quickstart）

目标：**一台 Linux 主机 + Docker，十分钟内跑起 CodeWise 全栈**（前端、网关、7 个业务服务、MySQL/Redis/RabbitMQ/Nacos、判题沙箱）。生产部署与运维要求见 [`OPERATIONS.md`](OPERATIONS.md)。

## 前置条件

- Docker 24+ 与 docker compose v2 插件；
- 内存 ≥ 8G、磁盘 ≥ 20GB。

## 三步启动

```bash
cd CodeWise/deploy
cp .env.example .env
vi .env          # 必改：所有 change-me 项（Token/JWT/数据库/root 密码）

cd ..
docker compose -f deploy/docker-compose.yml up -d --build
```

首次启动会自动完成：

- MySQL 建库 + 全部建表/迁移 SQL（[`mysql-init/01-init-databases.sh`](mysql-init/01-init-databases.sh)）；
- root 管理员创建（`ROOT_USERNAME`，默认 `admin`；密码取 `ROOT_PASSWORD`，`sys_init` 表幂等）；
- RabbitMQ 拓扑与判题容器池预热。

```bash
docker compose -f deploy/docker-compose.yml ps   # 等待全部 healthy（首次含镜像构建，约 10-20 分钟）
curl http://localhost:8082/actuator/health        # {"status":"UP"}
```

打开 `http://localhost/`，用 `admin` 登录。

## 各功能的可用前提（重要）

服务**全部可以启动**，但以下功能在缺配置时静默降级：

| 功能 | 需要的配置 | 缺失时的表现 |
| --- | --- | --- |
| 注册/找回密码的验证码邮件 | Nacos `service-email.yaml` 配置 SMTP | 邮箱注册不可用，用户名+密码登录正常 |
| 平台内置 AI 模型 | Nacos 配置 Provider 列表 | 建议生成失败；可改用页面上的**用户自定义模型** |
| 用户自定义模型 | `.env` 的 `API_KEY_MASTER_KEY`（32 字节 Base64，`openssl rand -base64 32`） | 保存自定义模型配置时报错 |
| Java 判题 | 镜像 `codewise-java-judge:17`（`up --build` 已自动构建） | Java 题判题一直 PENDING |

## 构建了什么

- 8 个服务镜像：共用模板 [`Dockerfile.service`](Dockerfile.service)，`--build-arg MODULE=...` 区分；
- 判题基础镜像：[`judge-base/Dockerfile`](judge-base/Dockerfile)（JDK17 + uid 1000 judge 用户）；
- 前端镜像：compose 内联构建 `../CodeWise-frontend/CodeWise-frontend` 并挂载 [`nginx.conf`](nginx.conf)。

## 遇到问题

- 服务间 Feign 503、判题 PENDING、跨域报错等见 [`OPERATIONS.md`](OPERATIONS.md) 第 7 节排障清单；
- `docker compose -f deploy/docker-compose.yml logs -f service-judge` 是判题问题的第一入口。

## 发布说明（维护者）

项目仍在快速迭代，**镜像发布流水线已就绪但默认不自动发布**：`.github/workflows/docker-images.yml` 仅支持手动触发，push/tag 触发已注释。切换到 GHCR 拉取镜像的完整步骤见 [`OPERATIONS.md`](OPERATIONS.md) 第 8 节。
