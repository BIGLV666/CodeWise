# CodeWise 文档导航

文档已按“全局文档 + 服务目录”整理。全局文档只维护跨服务约定，各服务的接口、实现说明和专题内容统一放在对应的 `service-*` 子目录，避免重复维护。

## 快速入口

| 目标 | 文档 | 适用场景 |
| --- | --- | --- |
| 了解项目和启动方式 | [`README.md`](../README.md) | 第一次接触项目、准备本地运行 |
| 查看服务边界和调用链 | [`docs/technical-design.md`](technical-design.md) | 设计评审、跨服务开发 |
| 定位代码与文档目录 | [`docs/project-structure.md`](project-structure.md) | 熟悉仓库、查找实现位置 |
| 查找 HTTP 接口 | [`docs/backend-controller-api.md`](backend-controller-api.md) | 前后端联调、接口排查 |
| 排查运行问题 | [`docs/maintenance-guide.md`](maintenance-guide.md) | 本地或测试环境故障处理 |

## 服务与模块文档

| 服务/模块 | 文档内容 |
| --- | --- |
| [`service-gateway`](service-gateway/README.md) | 网关职责、鉴权透传及相关全局文档入口 |
| [`service-user`](service-user/api.md) | 注册、登录、用户资料和头像接口 |
| [`service-question`](service-question/api.md) | 题目、函数题、测试点、提交与调试接口 |
| [`service-review`](service-review/api.md) | 收藏夹、复习计划、配置与提醒接口 |
| [`service-community`](service-community/api.md) | 帖子、题解、评论、点赞、审核与申诉 |
| [`service-message`](service-message/README.md) | 通知中心、邮件、MQ 与 WebSocket |
| [`service-ai`](service-ai/README.md) | AI 建议、SSE 会话、自定义模型和测试生成 |
| [`service-judge`](service-judge/README.md) | 判题处理、容器池、死信重试、补偿和管理接口 |
| [`service-judge-go`](service-judge-go/README.md) | Go 判题原型说明 |
| [`service-api`](service-api/README.md) | Feign 契约、共享 DTO 与 `Result<T>` |
| [`service-common`](service-common/README.md) | JWT、Redis、RabbitMQ 和 `UserContext` 基础设施 |

## 全局架构与业务

- [`docs/technical-design.md`](technical-design.md)：服务边界、认证透传、Feign、RabbitMQ、Redis 和判题链路。
- [`docs/codewise-flow-and-features.md`](codewise-flow-and-features.md)：刷题、判题、复习、社区、通知和 AI 的业务闭环。
- [`docs/project-structure.md`](project-structure.md)：主要目录、模块职责、数据库边界和消息依赖。
- [`docs/project-metrics.md`](project-metrics.md)：按固定口径记录的工程规模快照。
- [`docs/backend-controller-api.md`](backend-controller-api.md)：统一调用约定、服务接口索引、内部接口和 DTO 速查。

## 发布与维护

- [`docs/maintenance-guide.md`](maintenance-guide.md)：部署前检查、判题容器池、消息队列、Redis 和常见故障。
- [`docs/maintenance/repair-plan.md`](maintenance/repair-plan.md)：按优先级维护的修复与演进计划。
- [`docs/maintenance/messaging-reliability.md`](maintenance/messaging-reliability.md)：MQ 可靠性改造说明（Outbox、信封、DLX、重放手册）。
- [`docs/v1-release-notes.md`](v1-release-notes.md)：V1 发布基线和部署前提。

## 文档维护约定

1. 服务专属内容放入对应 `docs/service-*/` 目录；不要在根目录再创建同主题副本。
2. 新增或修改 Controller 后，更新对应服务文档；全局接口总览只维护链接和公共约定。
3. 修改服务端口、网关路由、MQ routing key、环境变量或数据库边界时，同步更新架构文档和维护手册。
4. 示例中的 Token、API Key、密码和内部地址必须使用占位符，不提交真实凭据。
5. 文档中的路径以仓库根目录为基准，移动文件后应检查 Markdown 相对链接。
