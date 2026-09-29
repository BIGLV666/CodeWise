# service-common 公共基础设施模块

`service-common` 提供 JWT、Redis、RabbitMQ、用户上下文和服务间身份透传等共享基础设施。它不是独立运行的业务服务。自动配置经 `META-INF/spring/...AutoConfiguration.imports` 注册（RedisConfig、MqConfig、UserAuthInterceptor、AdminAuthAspect、EmailService、RateLimiterConfig、FeignHeaderAutoConfiguration、EventAutoConfiguration、CodewiseOutboxProAutoConfiguration），引入本模块的服务自动生效。

## 维护约定

- 只放通用基础设施，不放具体业务逻辑（`Result<T>` 在 service-api，不放这里）。
- MQ 常量和 Bean 定义集中维护（`config/MqContexts` + `config/MqConfig`），业务服务复用统一配置，不硬编码队列名。
- 下游服务从 `UserContext`（`until/` 包，注意拼写是 until 不是 util；ThreadLocal 存 userId/userName/ip，`clear()` 在 afterCompletion 调用）获取可信用户身份，不接收客户端自报的 `userId`。
- `UserAuthInterceptor` 先校验网关注入的内部调用令牌（`X-Internal-Token`，比对 `codewise.internal-token`，无默认值），校验通过后才允许进入匿名白名单：精确路径白名单（登录/注册等 9 条）+ Ant 模式 `/uploads/**`、`/websocket/**`、`/ws/**`、`/sockjs/**`，不用关键字包含匹配；`/actuator/health` 等探针在 token 校验前放行；`/api/*/governance` 经 Feign 回查 roleId 做管理员校验。
- `FeignHeaderAutoConfiguration`：服务间 Feign 调用统一再注入 `X-Internal-Token` + 身份三件套（`X-User-Id`/`X-User-Name`/`X-Real-IP`，从 `UserContext` 读）。
- `until/JwtUntil`：HS 对称签名工具，密钥经构造器注入（`jwt.secret: ${JWT_SECRET}`），源码不硬编码；Python 侧 `JWT_SECRET` 必须同值。
- `aop/`：`@RequireAdmin` 注解 + `AdminAuthAspect` 切面（管理员 roleId==2 或 root roleId==0）。拒绝原因文案取自 service-api `CommonErrorCode`（未登录/回查失败 → `LOGIN_REQUIRED`，非管理员 → `ADMIN_ONLY`），经 `FilterContext.setRejectReason` 由治理过滤器链转成响应，切面内不抛异常。
- `advice/AbstractGlobalExceptionHandler`：六个业务服务 `GlobalExceptionHandler` 的公共基类（`@Order(HIGHEST_PRECEDENCE)`，保证优先于 api-governance starter 自带的 `GovernanceExceptionHandler`）。规则：**只有 `BizException` 的消息面向用户原样返回**；`GovernanceException` 按 status 归一到 `LOGIN_REQUIRED`/`NO_PERMISSION`/`RATE_LIMITED` 码段并保留其文案；参数/校验/类型不符/请求体不可读 → 参数类码；`DuplicateKeyException` → `DUPLICATE_RESOURCE`；其余 `DataAccessException` → `DATA_OPERATION_FAILED`；`IllegalStateException` → `STATE_CONFLICT`；兜底 `Exception` → `INTERNAL_ERROR`。HTTP 恒 200，原始异常只进日志——原先 question/review/user 三个 handler 里「系统繁忙 + e.getMessage()」的 NullPointerException、IllegalAccessError 分支已随基类接入删除。
- `exception/InfraErrorCode`（服务号 08）：本模块内部失败文案（信封格式/解析/序列化、必需配置缺失、运维操作未开放）。这些消息只进日志与死信台账，不回传前端；`event/EnvelopeCodec`、`until/JwtUntil`、`outboxpro/CodewiseDlqReadAuthorizer` 的文案由此而来，不再拼接原始异常文本。

## RabbitMQ（`MqConfig`，需 `codewise.mq.enabled=true`；统一 `Jackson2JsonMessageConverter`）

MqContexts 常量（Direct 交换机；判题/AI 旧 `judge.queue`/`ai.queue` 因 broker 参数不可变无法补挂 DLX 已弃用，拆为 v2 独立队列统一挂 DLX，配 TTL 等待队列实现无插件延迟重试）：

- 消息/邮件：`message.queue` / `message.exchange`，路由键 `email.routing`、`websocket.routing`
- 判题：`judge.exchange`；队列 `judge.submit.queue`、`judge.debug.queue`、`judge.retry.queue`、`judge.wait.queue`（TTL 弹回）；路由键 `judge.routing`、`judge.debug.routing`、`judge.retry.routing`；死信 `judge.dlx` → `judge.dead.queue`（rk `judge.dead`）
- AI：`ai.exchange`；队列 `ai.testcase.queue`、`ai.advice.queue`、`ai.testcase.wait.queue`、`ai.advice.wait.queue`；路由键 `ai.routing`、`ai.testcase.routing`、`ai.wa-advice.routing`；死信 `ai.dlx` → `ai.dead.queue`（rk `ai.dead`）
- 题目：`question.queue` / `question.exchange`，路由键 `question.testcace.routing`（注意既有拼写）、`question.delete.queue.routing`、`question.submit.record.routing`、`question.debug.routing`
- 复习：`reviews.queue` / `reviews.exchange`（rk `reviews.judge.record.routing`）+ 独立 `reviews.plan.queue`（rk `plan.judge.record.routing`，与复习消费分开避免竞争消费者丢消息）
- 用户：`user.queue` / `user.exchange`（rk `user.judge.routing`）
- 收件箱：`notification.queue` / `notification.exchange`，路由键 `notification.like/review/appeal/checked/ai.advice/review.mastered.routing`
- WebSocket 主题（`WebsocketContexts`）：`judge-result`、`debug-judge-result`、`topic`

## OutboxPro 与事件信封

- 需与 DB 事务原子一致的发布走 OutboxPro（`OutboxProPublisher.publish`，事务内写 outbox 表）；事务外尽力而为（WebSocket 推送）走 `event/EventPublisher`（信封头携带 eventId/eventType/producer/traceId）。消费端 `EnvelopeCodec.unwrap` 信封/裸格式双读，灰度兼容。
- `outboxpro/`：`CodewiseOutboxProAutoConfiguration`（死信告警 `CodewiseOutboxAlertNotifier` + DLQ 重放只读授权 `CodewiseDlqReadAuthorizer`），仅 `outboxpro.enabled=true` 的生产者服务（question/judge/review/community）生效，其余服务必须显式 false。
- 其余：`RedisConfig`（JSON 序列化支持 LocalDateTime）、`RateLimiterConfig`（登录用户按 userId、匿名按 IP 且配额放大 20 倍）、`RedisDto/`（判题调试缓存 DTO）、`dto/`（MQ 载荷 DTO）。

相关整体约定见 [`../technical-design.md`](../technical-design.md) 和 [`../project-structure.md`](../project-structure.md)。
