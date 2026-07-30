# CodeWise V1 发布基线

> 基线日期：2026-07-30
> 目标版本：`v1.0.0`

## 版本定位

V1 是 CodeWise Java 微服务实现的首个稳定基线，覆盖在线判题、函数题、复习、社区、通知和 AI 辅助的完整主链路。

## 已封档能力

- Gateway JWT 认证、用户上下文透传和下游资源权限校验。
- ACM 模式代码调试、异步提交、判题结果持久化和实时通知。
- Java 函数模式题面解析、方法签名适配、批量调试和单 JVM 批量判题。
- 函数测试批量补充、输入输出哈希去重、随机输入生成和标准答案校验。
- Docker 多语言容器池、阻塞租借、等待计数、失败重建和管理员动态扩缩容。
- 错题复习、收藏夹、社区题解、评论、点赞、热点排行和站内通知。
- AI 判题建议、代码差异建议、SSE 会话、记忆压缩和用户自定义模型配置。

## 部署前提

基础设施：MySQL 8、Redis、RabbitMQ、Nacos、Docker、JDK 21。

必须由部署环境提供的配置：

| 配置 | 使用服务 | 用途 |
| --- | --- | --- |
| `CODEWISE_INTERNAL_TOKEN` | `service-ai`、`service-question` | 内部函数产物生成接口鉴权，两端值必须一致 |
| `API_KEY_MASTER_KEY` | `service-ai` | 加密用户自定义模型密钥 |
| JWT 密钥 | `service-gateway` | 登录令牌签发与验证，正式环境不得使用仓库默认值 |

函数产物默认保存在 `data/function-artifacts`。该目录是运行数据，不属于源码和发布包内容。

## 发布验证

公共模块先安装：

```powershell
.\mvnw.cmd -f service-common\pom.xml -DskipTests install
.\mvnw.cmd -f service-api\pom.xml -DskipTests install
```

核心模块验证：

```powershell
.\mvnw.cmd -f service-question\pom.xml test
.\mvnw.cmd -f service-judge\pom.xml test
.\mvnw.cmd -f service-ai\pom.xml test
.\mvnw.cmd -f service-gateway\pom.xml -DskipTests compile
```

人工冒烟流程：

1. 管理员登录并确认各服务已注册到 Nacos。
2. 打开 ACM 题目，完成调试、提交并收到判题结果。
3. 打开 Java 函数题，验证样例调试和批量测试提交。
4. 制造 WA，确认 AI 建议、提交详情和错题复习记录可查询。
5. 查询容器池状态，扩容一个 Java 容器后再删除该空闲容器。
6. 重启业务服务，确认数据库记录仍可查询，运行时缓存可以重新建立。

## V1 运行范围

- 函数题以 Java 为主，ACM 模式支持 Java、Python、C 和 C++。
- 判题容器池由单个 `service-judge` 实例维护，管理接口展示当前实例快照。
- RabbitMQ 负责任务解耦，WebSocket 与 SSE 分别承担结果通知和 AI 流式响应。
- 通用随机生成器负责基础类型，题目级 Generator 与 Validator 负责复杂参数约束。

## 后续演进

后续版本将围绕 Go 判题节点、多节点调度、沙箱强化、Prometheus/Grafana、全链路 traceId、复杂自动对拍策略和 AI Agent 扩展独立推进。V1 分支进入稳定维护阶段。
