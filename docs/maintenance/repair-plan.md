# CodeWise 待修复清单与演进计划

> 本文档只记录需要修复、验证或后续提升的事项，按面试前优先级排序。
>
> 原则：先修正确性和安全性，再补可靠性，最后做性能和平台化建设。

## 一、P0：面试前必须修复

### 1. 身份与授权

- [-] 修复 `service-question` 删除题目的 owner/admin 授权校验。
- [-] 修复提交记录删除接口的 IDOR 风险。
- [-] 函数题创建时禁止信任客户端提交的 `createUserId`。
- [-] 函数测试用例批量插入增加 owner/admin 校验。
- [ ] Gateway 转发前删除客户端提交的 `X-User-Id`、`X-User-Name`、`X-Internal-Token`、`X-Real-IP`，再注入可信值。
- [ ] 将内部 Token、JWT Secret、其他敏感配置移出源码，改为环境变量、Nacos 加密配置或密钥管理服务。
- [-] 收紧 `UserAuthInterceptor` 的匿名放行规则，删除基于 `contains("login")`、`contains("/info")`、`contains("uploads")` 的宽泛匹配。
- [-] WebSocket 入口不能完全绕过内部身份校验。

### 2. Judge MQ 可靠性

- [ ] 为主 Judge 队列真正配置 DLX/DLQ 参数，确认 `basicNack(requeue=false)` 会进入死信队列。
- [ ] 拆分 submit、debug、retry 队列，避免不同消息结构共用一个队列。
- [-] 确认并启用 `@EnableScheduling`，验证 Pending 任务补偿扫描实际运行。
- [ ] 统一 ACK/NACK 时机，避免事务提交前 ACK。
- [ ] 避免在数据库事务中长时间执行 Docker 和 MQ 操作。
- [ ] 为判题结果更新增加原子幂等控制，防止重复回调重复增加计数。
- [ ] 增加消息消费日志：eventId、submitId、routingKey、重试次数和最终结果。

### 3. 判题沙箱

- [-] 限制 CPU、内存、PID、文件大小和输出大小。
- [-] 使用非 root 用户运行代码。
- [-] 启用只读根文件系统。
- [-] `cap-drop=ALL`，启用 `no-new-privileges`。
- [-] 禁止或严格限制容器网络访问。
- [-] 增加进程树清理，防止子进程残留和 fork bomb。
- [-] 避免使用不安全的 Docker TCP 2375。
- [ ] AI 生成的执行产物默认进入沙箱，不允许宿主机执行。

### 4. Community 正确性

- [-] 使用 Lua 原子完成“读取当前桶 + 自增”或修正切桶并发窗口。
- [-] 从公共帖子缓存中移除用户维度的 `isLike` 字段。
- [-] 内容下架、删除、审核状态变化后主动失效相关缓存。
- [ ] 修复帖子详情读取错误 Redis Key 的问题。
- [-] 修复 Solution `isLike` 查询缺少当前用户条件的问题。
- [-] 申诉处理改为事务 + 条件更新，避免并发处理覆盖。
- [-] 异步删除不要只依赖裸 `CompletableFuture`，改用 MQ 或受控线程池。
- [-] 热榜重建使用临时 Key 构建完成后原子切换。

### 5. Message 与 AI 消费可靠性

- [ ] 修复“先写 pending/幂等标记，后执行业务”的消息丢失风险。
- [ ] 为事件增加数据库唯一 `eventId`，并增加 `PROCESSING/COMPLETED/FAILED` 状态。
- [ ] 邮件发送失败不能直接 ACK，应支持重试和失败保留。
- [ ] AI 建议通知发送失败时不能把业务状态提前标记为完成。
- [ ] 为 AI 队列增加 DLX、重试队列和死信处理。
- [ ] 为 Assistant 消息增加 `GENERATING/COMPLETED/FAILED/CANCELLED` 状态。
- [ ] SSE 错误事件不要直接返回原始异常信息。

### 6. Review 正确性

- [ ] Mastered 题目答错后必须回退到 Active 或待复习状态。
- [ ] 增加事件级幂等表，防止同一判题结果重复更新复习计划。
- [ ] 修复 Redis 幂等标记先于数据库提交造成的误判。
- [ ] 提醒消息发布增加 Outbox 或 Publisher Confirm。

## 二、P1：可靠性和可维护性

### 消息与事务

- [ ] 实现 Transactional Outbox，优先覆盖 Question → Judge 主链路。
- [ ] 统一 MQ 消息信封：`eventId`、`eventType`、`schemaVersion`、`occurredAt`、`producer`、`traceId`、`payload`。
- [ ] 增加延迟重试、指数退避、DLQ 和人工重放能力。
- [ ] 拆分代码、日志、程序输出等大字段，避免放入 MQ 大消息。
- [ ] 统一各服务 FeignRequestInterceptor，减少重复实现。
- [ ] 将 Judge 的 submit/debug/retry 处理器拆成清晰的独立消费者。
- [ ] 拆分过大的 `JudgeService.java`，按任务领取、容器执行、结果处理、补偿恢复拆分。

### AI

- [ ] SSE 与摘要任务使用独立有界线程池，并支持超时、取消和拒绝策略。
- [ ] 摘要输出增加 JSON Schema 校验，非法摘要保留旧版本。
- [-] 摘要查询按 `message_id > end_message_id LIMIT 14` 增量读取。
- [-] 自定义模型 URL 增加 DNS 重绑定、全部解析 IP、重定向校验。
- [ ] 限制模型列表、单 chunk 和完整回答的最大字节数。
- [ ] API Key 密文增加 `keyId/version`，支持主密钥轮换和重加密。
- [-] Provider 增加真实健康检查和 Circuit Breaker。
- [ ] 删除重复 QWEN Provider 和重复 routing key Handler。
- [ ] 下线旧版直接生成 expected output 的链路，统一使用 Generator + Standard Main 验收。
- [ ] AI 失败时禁止通过破坏性删除题目进行补偿，改为失败状态或人工审核。

### 数据与 DTO

- [ ] 拆分敏感 `UserDto`，避免公开接口携带邮箱、手机号、openId、登录 IP 等字段。
- [ ] 统一 `Long userId` 与 `String userId` 类型。
- [ ] 为 `Result<T>` 增加结构化错误码、traceId 和可安全展示的 message。
- [ ] 删除 common/api 中重复 DTO，统一事件 DTO 所有权。
- [ ] 为 Redis Key 建立集中注册表和 TTL 策略。
- [ ] 评估 Redis Default Typing 的反序列化攻击面，改为白名单类型或显式序列化。
- [ ] 管理员鉴权增加短 TTL 缓存和用户服务降级策略。

## 三、P2：性能、观测和工程化

- [ ] 接入 Prometheus/Grafana。
- [ ] 接入 OpenTelemetry，贯通 Gateway、Feign、RabbitMQ、Judge 和通知链路。
- [ ] 采集容器借用等待 P95/P99、容器利用率、判题端到端耗时、DLQ 数量。
- [ ] 采集 AI 首 Token 延迟、Provider 失败率、SSE 中断率。
- [ ] 使用 Testcontainers 补充 MySQL、Redis、RabbitMQ 集成测试。
- [ ] 增加恶意代码、资源耗尽、消息重复、MQ 重放、WebSocket 断连测试。
- [ ] 做真实并发压测后再确定 QPS、P95 和性能提升数据。
- [ ] Redis 热榜重建使用临时 Key + 原子切换。
- [ ] 复核社区申诉、下架历史等路径中的剩余 N+1 查询。

## 四、Go Judge 演进计划

当前 `service-judge-go` 应定位为实验性 Java ACM 执行器，不应直接宣传为已完成替换。

### 阶段一：安全与正确性

- [ ] Docker 化执行不可信代码。
- [ ] 增加 CPU、内存、超时、输出和进程限制。
- [ ] 增加数据库事务和 CAS 状态更新。
- [ ] 增加 eventId/submitId 幂等。
- [ ] 发布消息使用 Publisher Confirm。
- [ ] 补齐 DLQ、重试和 ACK/NACK 策略。

### 阶段二：验证

- [ ] 增加单元测试和 RabbitMQ/MySQL 集成测试。
- [ ] 采用 shadow judging，与 Java Judge 结果对比。
- [ ] 对比编译耗时、执行耗时、错误率和资源消耗。

### 阶段三：灰度

- [ ] 只对 ACM Java 题开放小比例流量。
- [ ] 出现结果不一致时以 Java Judge 为最终结果。
- [ ] 指标稳定后再逐步扩大范围。

## 五、建议的验收标准

完成 P0 后至少满足：

- 越权删除、伪造 userId、伪造内部请求头均有回归测试；
- Judge 消费失败能够进入 DLQ 并可人工重放；
- 重复判题结果不会重复累加；
- 容器无法访问宿主机敏感资源；
- WebSocket 断线后可通过数据库查询最终判题结果；
- 邮件失败能够重试或进入失败记录；
- AI 生成的测试产物必须经过确定性编译和执行验收；
- Review、Community 和 Message 的核心状态不会因重复消息或并发请求错误覆盖。
