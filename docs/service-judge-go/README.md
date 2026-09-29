# service-judge-go 判题原型目录

该目录作为 Go 判题实现/原型单独保留，不参与 Docker 部署（见 `deploy/docker-compose.yml` 顶部注释）。CodeWise 当前正式异步判题主链路仍以 [`service-judge`](../service-judge/README.md) 文档为准。

## 当前实现状态（实验性骨架）

- **执行器已桩化**：`internal/executor` 的 Java 执行器一律返回 `SYSTEM_ERROR`，宿主机直接执行 `javac/java` 的路径已移除；服务层（`internal/service/judge.go`）对非 Java 语言、函数模式（非 ACM）题目与测试用例为空同样返回 `SYSTEM_ERROR`。
- **仅保留 HTTP/MQ 骨架**：gin HTTP 服务提供 `GET /health`、`POST /api/judge/execute`、`POST /api/judge/submit/:id`（监听端口由 `JUDGE_HTTP_PORT` 配置，默认 8087）；MySQL/Redis 启动时连接校验；RabbitMQ 消费默认关闭，经 `JUDGE_MQ_ENABLED=true` 开关启用。
- **MQ 契约与主链路不一致**：消费者仍指向旧 `judge.queue`（Java 侧已弃用该队列），且按裸 JSON 数字解析消息体，不兼容当前 OutboxPro 事件信封格式——即使开启也无法承接现网判题流量。
- Docker 化执行（经 Docker SDK HTTP API，配置 network none / 资源限制 / 只读根 / noexec tmpfs / 容器内超时）尚未实现，实现计划见 `docs/maintenance/repair-plan.md` 的「Go Judge 演进计划」章节。

在执行器落地前，该服务不应承载真实判题流量，也不应宣传为可用的 Go 判题节点。另注意：仓库内 `service-judge-go/README.md` 中「当前执行器使用本机 javac 和 java」的描述已过时（执行器已桩化），以本文为准。

如后续启用 Go 判题服务，应在本目录补充运行方式、消息契约、容器隔离和与 Java 判题服务的切换策略，避免与正式链路文档混用。
