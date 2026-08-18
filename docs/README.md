# CodeWise 文档导航

CodeWise 文档按“快速上手、架构设计、接口参考、模块专题、运维发布”分组。建议先阅读根目录 [`README.md`](../README.md)，再根据开发或部署任务选择对应文档。

## 快速入口

| 目标 | 文档 | 适用场景 |
| --- | --- | --- |
| 了解项目和启动方式 | [`README.md`](../README.md) | 第一次接触项目、准备本地运行 |
| 查看服务边界和调用链 | [`technical-design.md`](technical-design.md) | 设计评审、跨服务开发 |
| 定位代码目录 | [`project-structure.md`](project-structure.md) | 熟悉仓库、查找实现位置 |
| 查找 HTTP 接口 | [`backend-controller-api.md`](backend-controller-api.md) | 前后端联调、接口排查 |
| 排查运行问题 | [`maintenance-guide.md`](maintenance-guide.md) | 本地或测试环境故障处理 |

## 架构与业务

- [`technical-design.md`](technical-design.md)：服务边界、认证透传、Feign、RabbitMQ、Redis 和判题链路。
- [`codewise-flow-and-features.md`](codewise-flow-and-features.md)：刷题、判题、复习、社区、通知和 AI 的业务闭环。
- [`project-structure.md`](project-structure.md)：主要目录、模块职责、数据库边界和消息依赖。
- [`project-metrics.md`](project-metrics.md)：按固定口径记录的工程规模快照。

## 接口文档

- [`backend-controller-api.md`](backend-controller-api.md)：Java 服务 Controller 接口总览。
- [`service-review-api.md`](service-review-api.md)：复习与收藏接口。
- [`service-community-api.md`](service-community-api.md)：社区帖子、题解、评论和点赞接口。
- [`service-message.md`](service-message.md)：通知中心、邮件和 WebSocket 说明。
- [`custom-ai-config-api.md`](custom-ai-config-api.md)：用户自定义 AI 服务配置接口。
- [`function-testcase-generator-api.md`](function-testcase-generator-api.md)：函数题测试用例生成接口。

## 社区审核与申诉

- [`community/review-appeal-api.md`](community/review-appeal-api.md)：管理员审核、下架/恢复、用户申诉和“我的内容”接口。
- [`community/review-appeal-summary.md`](community/review-appeal-summary.md)：数据表、消息通知、权限和实现清单。

## AI 与 Agent

- [`AI_MODULE_GUIDE.md`](AI_MODULE_GUIDE.md)：`service-ai` 的判题建议、题内追问、SSE 和会话记忆。
- 根目录 [`README.md`](../README.md) 的 Agent 小节：同级 `CodeWise-Agent` 的运行方式、JWT 和数据库配置。

## 发布与维护

- [`maintenance-guide.md`](maintenance-guide.md)：部署前检查、判题容器池、消息队列、Redis 和常见故障。
- [`v1-release-notes.md`](v1-release-notes.md)：V1 发布基线和部署前提。

## 文档维护约定

1. 新增或修改 Controller 后，同步更新接口总览或对应模块专题文档。
2. 修改服务端口、网关路由、MQ routing key、环境变量或数据库结构时，优先更新架构文档和维护手册。
3. 跨服务功能的详细文档放入对应专题目录；根目录文档只保留导航和稳定的全局约定。
4. 示例中的 Token、API Key、密码和内部地址使用占位符，不提交真实凭据。
5. 文档中的路径以仓库根目录为基准，链接变更后应检查文件是否存在。
