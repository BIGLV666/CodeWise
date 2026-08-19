# service-common 公共基础设施模块

`service-common` 提供 JWT、Redis、RabbitMQ、用户上下文和服务间身份透传等共享基础设施。它不是独立运行的业务服务。

## 维护约定

- 只放通用基础设施，不放具体业务逻辑。
- MQ 常量和 Bean 定义集中维护，业务服务复用统一配置。
- 下游服务从 `UserContext` 获取可信用户身份，不接收客户端自报的 `userId`。

相关整体约定见 [`../technical-design.md`](../technical-design.md) 和 [`../project-structure.md`](../project-structure.md)。
