# service-judge-go 判题原型目录

该目录作为 Go 判题实现/原型单独保留。CodeWise 当前正式异步判题主链路仍以 [`service-judge`](../service-judge/README.md) 文档为准。

## 当前实现状态（实验性骨架）

- **执行器已桩化**：`internal/executor` 的 Java 执行器一律返回 `SYSTEM_ERROR`，宿主机直接执行 `javac/java` 的路径已移除；非 Java 题型、非函数模式或用例为空时同样返回 `SYSTEM_ERROR`。
- **仅保留 HTTP/MQ 骨架**：gin HTTP 服务、MySQL/Redis 访问，以及可通过 `JUDGE_MQ_ENABLED` 开关启停的 RabbitMQ 消费。
- Docker 化执行（经 Docker SDK HTTP API，配置 network none / 资源限制 / 只读根 / noexec tmpfs / 容器内超时）尚未实现，实现计划见 `docs/maintenance/repair-plan.md` 的 Go Judge 演进章节。

在执行器落地前，该服务不应承载真实判题流量，也不应宣传为可用的 Go 判题节点。

如后续启用 Go 判题服务，应在本目录补充运行方式、消息契约、容器隔离和与 Java 判题服务的切换策略，避免与正式链路文档混用。
