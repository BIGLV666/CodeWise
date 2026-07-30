# CodeWise V1 发布基线

> 基线日期：2026-07-30
> 目标版本：`v1.0.0`

## 版本定位

V1 是 CodeWise 当前 Java 微服务实现的可演示、可维护基线，覆盖在线判题、函数题、复习、社区、通知和 AI 辅助的完整主链路。V1 不宣称具备生产级沙箱、多机容灾或完整微服务治理能力。

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

## V1 已知限制

- 判题容器仍缺少 PID、CPU、输出大小、只读文件系统和非 root 用户等完整沙箱限制。
- RabbitMQ publisher confirm、死信队列和统一失败补偿尚未完成。
- Feign 超时、重试、熔断和统一异常结构尚未完全收敛。
- WebSocket 多实例会话路由、分布式链路追踪和统一指标平台尚未实现。
- 函数题当前只支持 Java；复杂跨参数随机约束仍需要题目级 Generator 与 Validator。
- 容器池状态是单个 `service-judge` 实例的本地快照。

## V2 边界

以下内容不再进入 V1：Go 判题机替换、多节点判题调度、生产级沙箱、Prometheus/Grafana、全链路 traceId、复杂自动对拍策略和进一步的 AI Agent 扩展。新增开发从独立里程碑开始，V1 仅接受阻断缺陷修复。
