# CodeWise Docker 快速开始（Quickstart）

目标：**一台 Linux 主机 + Docker，十分钟内跑起 CodeWise 全栈**（前端、网关、7 个业务服务、Python Agent、MySQL/Redis/RabbitMQ/Nacos、Mailpit 邮件捕获共 15 个常驻容器；另有一个一次性 judge-base 构建任务容器，判题沙箱为运行期动态创建的临时容器）。生产部署与运维要求见 [`OPERATIONS.md`](OPERATIONS.md)。

## 前置条件

- Docker 24+ 与 docker compose v2 插件；
- 内存 ≥ 10G（常驻容器 mem_limit 合计约 8.8G，判题沙箱容器另各限 256m）、磁盘 ≥ 20GB。

## 三步启动

```bash
cd CodeWise/deploy
cp .env.example .env
vi .env          # 必改：所有 change-me 项（Token/JWT/数据库/root 密码）

cd ..
docker compose -f deploy/docker-compose.yml up -d --build
```

首次启动会自动完成：

- MySQL 建库 + 业务账号全库授权 + 建表 SQL（增量变更已合入基础脚本；[`mysql-init/01-init-databases.sh`](mysql-init/01-init-databases.sh)）；
- OutboxPro 事件表（`outboxpro_*`）由生产者服务首次启动时自动创建；
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
| 注册/找回密码的验证码邮件 | 默认发往容器内 Mailpit（Web 界面 `127.0.0.1:8025` 查看）；接真实 SMTP 在 `.env` 配 `SPRING_MAIL_*` | 邮件进 Mailpit，不丢失 |
| 平台内置 AI 模型 | Nacos 配置 Provider 列表 | 建议生成失败进死信留痕；可改用页面上的**用户自定义模型** |
| 用户自定义模型 | `.env` 的 `API_KEY_MASTER_KEY`（32 字节 Base64，`openssl rand -base64 32`） | 保存自定义模型配置时报错 |
| Java 判题 | 镜像 `codewise-java-judge:17`（`up --build` 已自动构建） | Java 题判题一直 PENDING |

## 构建了什么

- 8 个服务镜像：共用模板 [`Dockerfile.service`](Dockerfile.service)，`--build-arg MODULE=...` 区分；容器内 Maven 多阶段构建按 `-N install` 根 POM → `service-api` → `service-common` → 目标模块的顺序执行（仓库无聚合 POM）；运行阶段固定 JVM 参数 `-XX:MaxRAMPercentage=45.0 -XX:MaxMetaspaceSize=160m -Xss512k`，与 compose 各服务 `mem_limit`（384m–1.5g）配合防止容器 OOM kill；
- 判题基础镜像：[`judge-base/Dockerfile`](judge-base/Dockerfile)（JDK17 + uid 1000 judge 用户 + jackson 2.15.x 函数题依赖三件套内置到 `/opt/judge/lib`）；
- Python Agent 镜像：`codewise-agent/Dockerfile`（node:22-bookworm-slim 单镜像双运行时：Node 22 + Debian 自带 Python 3.11），nginx 以 `/agentapi/` 反代；
- 前端镜像：compose 内联构建兄弟仓库 `../../CodeWise-frontend`（pnpm + `VITE_AGENT_BASE_URL=/agentapi`）并挂载 [`nginx.conf`](nginx.conf)；[`frontend/Dockerfile`](frontend/Dockerfile) 为内容一致的独立构建参考。**前端仓库须与 CodeWise 同级**（`git clone https://github.com/BIGLV666/CodeWise-frontend.git` 到 CodeWise 的上级目录），否则前端镜像构建会因构建上下文不存在而失败。

## 遇到问题

- 服务间 Feign 503、判题 PENDING、跨域报错等见 [`OPERATIONS.md`](OPERATIONS.md) 第 7 节排障清单；
- `docker compose -f deploy/docker-compose.yml logs -f service-judge` 是判题问题的第一入口。
- `service-judge` 的 8086 是明文 HTTP 管理端口，当前 Compose 将其映射到宿主机。直接向它发送 HTTPS 会触发 Tomcat「Invalid character found in method name」；这与 JSON 或 WebSocket 消息解析无关。生产环境应通过防火墙或 TLS 反向代理限制入口；若无需宿主机访问，可在部署前移除 judge 的 `ports` 映射，容器间通信及容器内健康检查仍使用 Docker 网络。本次修复保留映射，以免影响现有运维访问。
- question/judge 本地配置默认启用公共 RabbitMQ 拓扑，并显式使用 JSON 消息转换器。Nacos 如覆盖 `codewise.mq.enabled` 为 `false`，需由运维预建队列/交换机/绑定；转换器仍独立启用。调试回调兼容历史裸 UUID 与新的 JSON 字符串，HTTP `/api/question/debug` 返回任务 UUID，最终结果由 WebSocket 推送。

## 发布说明（维护者）

项目仍在快速迭代，**镜像发布流水线已就绪但默认不自动发布**：`.github/workflows/docker-images.yml` 仅支持手动触发，push/tag 触发已注释。切换到 GHCR 拉取镜像的完整步骤见 [`OPERATIONS.md`](OPERATIONS.md) 第 8 节。
