# 消息可靠性改造说明（P1 消息与事务专项）

> 对应 `docs/maintenance/repair-plan.md` P1「消息与事务」条目的实现说明；第 9 节为 P0-5「Message 与 AI 消费可靠性」专项的实现说明。
> 范围：Transactional Outbox、统一消息信封、延迟重试/指数退避/DLQ/人工重放、大字段出 MQ、统一 Feign 拦截器、Judge 消费者拆分、JudgeService 拆分；Message/AI 消费幂等状态机（consumed_event）、AI 队列 v2 拓扑、ai_message 生成状态。

## 1. 新 MQ 拓扑（judge 主链路）

```
judge.exchange (direct)
 ├─ judge.routing       → judge.submit.queue  (DLX=judge.dlx, DLK=judge.dead)
 ├─ judge.debug.routing → judge.debug.queue   (DLX=judge.dlx, DLK=judge.dead)
 ├─ judge.retry.routing → judge.retry.queue   (DLX=judge.dlx, DLK=judge.dead)
 └─ judge.wait.queue：无消费者，DLX=judge.exchange / DLK=judge.routing
       消费失败 → per-message TTL(5s/10s/20s) → 到期弹回 judge.submit.queue
judge.dlx (direct) ── judge.dead ──→ judge.dead.queue
       └─ JudgeDeadLetterHandler：按 eventType 登记 failure_submit 或留痕
```

- **旧 `judge.queue` 已弃用**（RabbitMQ 队列参数不可变，无法补挂 DLX，这是它 `basicNack(requeue=false)` 实际等于丢弃的根因）。
- 队列声明集中在 `service-common` `MqConfig`，`codewise.mq.enabled=true` 时由 RabbitAdmin 启动即声明（幂等）。

### 发布/部署步骤

1. 构建部署全部后端服务（monorepo 同批发布；`AiAdviceWADto` 瘦身与 service-ai 改造强绑定）。
2. 排空旧队列：管理台（15672）把 `judge.queue` 中残留消息 moveTo `judge.submit.queue`（或等其自然消费完后删除旧队列）。
3. 新队列为空启动，无需预建。

## 2. 统一消息信封（EventEnvelope）

消息体 JSON：

```json
{
  "eventId": "uuid-幂等键",
  "eventType": "JUDGE_SUBMIT_REQUEST",
  "schemaVersion": 1,
  "occurredAt": "2026-08-22T12:00:00",
  "producer": "service-question",
  "traceId": "短ID",
  "payload": 123
}
```

- AMQP 头同步携带 `eventId/eventType/schemaVersion/producer/traceId/x-codewise-retry-count`，便于管理台检索与消费日志关联。
- 消费端用 `service-common` `EnvelopeCodec.unwrap(body, X.class)` 做**信封/裸格式双读**，灰度期两种格式并存均可消费。
- 约定：payload 只放 ID 引用；代码、日志、输入输出等 LONGTEXT 大字段一律落库（judge_record），需要方经 `QuestionFeignClient#getJudgeContext(judgeRecordId)` 按需拉取。WebSocket 推送的 JudgeResultDto 保留字段形状，但 code/log/expectedOutput/actual 按字符数截断至 16K（`error` 字段豁免截断）。

## 3. Transactional Outbox（OutboxPro）

> 2026-09 起 Outbox 实现从自研 `event_outbox` + `OutboxRelay` 切换为开源库
> [OutboxPro](https://github.com/biglv666/OutboxPro) 1.1.0
>（`io.github.biglv666:outboxpro-spring-boot-starter`，生产端零改动迁移：
> 事件体仍为统一信封 JSON，消费端 `EnvelopeCodec.unwrap` 双读不受影响）。

- 表：`outboxpro_outbox`（codewise_question 库，question 与 judge 共库共表，双 Relay 由
  `FOR UPDATE SKIP LOCKED` 保证并发安全；DDL 由各生产者服务启动时
  `outboxpro.schema-initialize` 自动创建，`IF NOT EXISTS` 幂等）。
- 写入：`OutboxProPublisher.publish(eventType, payload)` 必须在业务 `@Transactional` 内调用，
  事件行与业务写同事务提交，消除「DB 提交但消息未发」与「消息已发但 DB 回滚」。
  路由（exchange/routingKey）在各服务 `OutboxEventRouteConfig` 以 `EventDefinition` Bean 登记。
- 投递：内置 Relay 每秒批量认领（`FOR UPDATE SKIP LOCKED`，多实例/多服务安全），
  **Publisher Confirm 确认后才标 SENT**（比旧实现的「发送不抛异常」更强）；
  失败指数退避 1s * 2^n，5 次后转 DEAD 并写入 `outboxpro_dead_letter` 台账（含计数器/告警/回放语义）。
- 启用：服务 yaml `outboxpro.enabled: true`（question/judge/review/community 四个生产者已启用；
  user/message/ai/gateway 必须显式 `false`——OutboxPro 默认 `matchIfMissing=true`）。
  `outboxpro.producer.poll-interval` 必须显式配纯毫秒 `1000`（默认 `"1000ms"` 需要 Spring Framework 6.2，
  Boot 3.2.4 解析不了，启动即失败）。
- 覆盖事件：
  - question：JUDGE_SUBMIT_REQUEST（提交判题）、JUDGE_DEBUG_REQUEST（调试）、
    REVIEW_JUDGE_RECORD（复习场景判题结果转发 review）、AI_TESTCASE_REQUEST（题目导入触发的用例生成，原为事务内裸发）
  - judge：JUDGE_RESULT_CALLBACK（结果回调 question）、AI_ADVICE_REQUEST（AI 建议，WA/RE/TLE 时）
  - review：REVIEW_REMINDER（复习到期提醒，codewise_review 库独立 outboxpro_outbox 表）、
    REVIEW_MASTERED（掌握祝贺：SM-2 状态 0→1 时与更新同事务发布，payload 为
    ReviewMasteredDto，题目名由 message 消费端 Feign 补齐）
  - community：NOTIFICATION_APPEAL（申诉处理结果通知，与申诉状态更新同事务，
    替代原「事务外异步裸发 + Redis 预检」模式）
- 兼容性守卫测试：`service-common` 的 `OutboxProIntegrationTest`（Testcontainers 真库端到端）
  与 `OutboxWireCompatTest`（消息体形状契约），本地无 Docker 自动跳过。

## 4. 重试 / DLQ / 人工重放

| 通道 | 策略 | 人工重放 |
|------|------|----------|
| 消费失败（submit） | 延迟重试 5s/10s/20s（wait 队列 TTL 弹回），3 次后 nack 进 DLQ | DLQ → failure_submit 登记 → `POST /api/judge/failure/retry/{id}` |
| 消费失败（retry 流程） | 业务已记录 failure_submit=FAILURE，nack 进 DLQ 留痕 | 同上 |
| 消费失败（debug） | 写 Redis 错误结果 + 回调 question + ACK（前端可见失败原因） | 无需 |
| Outbox 投递失败 | 指数退避 1s*2^n，5 次转 DEAD（含台账） | `GET /actuator/outboxpro-ops/outbox`、`/dlq`（内部 Token，只读） |

毒消息（载荷解析失败）直接 nack 进 DLQ，不重试。

## 5. 消费日志约定

新消费者统一输出：eventId、eventType、routingKey、retryCount、业务键（submitId/uuid/failureId）、最终结果（ACK/RETRY/DEAD）。

## 6. 统一 Feign 拦截器

`service-common` `FeignHeaderAutoConfiguration` 提供全局 `commonFeignRequestInterceptor`：透传 X-User-Id/X-User-Name/X-Real-IP（来自 UserContext），注入 X-Internal-Token（读 `codewise.internal-token`，无默认值，经环境变量 `CODEWISE_INTERNAL_TOKEN` 注入，与网关 `AuthGlobalFilter`、`UserAuthInterceptor` 三处同值）。ai/community/judge/question/review 五份本地副本已删除。

## 7. 相关配置项速查

| 配置 | 默认 | 说明 |
|------|------|------|
| `outboxpro.enabled` | false（默认 matchIfMissing=true，须逐服务显式） | 生产者服务（question/judge/review/community）true，其余显式 false |
| `outboxpro.producer.poll-interval` | "1000ms"（6.2 语法） | **必须显式配 `1000`**：Boot 3.2.4（Framework 6.1）解析不了 "1000ms"，启动即失败 |
| `outboxpro.dlq.alert.threshold` | 100 | 死信积压高水位告警阈值（`OUTBOXPRO_ALERT` ERROR 日志 + 指标） |
| `codewise.internal-token` | 无（必填） | 内部通信 Token，经 `CODEWISE_INTERNAL_TOKEN` 环境变量注入，缺失启动失败 |
| `jwt.secret` | 无（必填） | JWT 密钥，经 `JWT_SECRET` 环境变量注入（gateway/message/review），与 Python Agent 同值 |
| `codewise.gateway.trust-forwarded-for` | false | 网关是否信任 X-Forwarded-For（部署在可信 LB 后才置 true，直连时只用 remoteAddress） |
| `codewise.mq.enabled` | - | 既有开关，控制 MqConfig 声明 |

## 8. 已知限制与后续项

- publisher confirm 未启用（依赖 Nacos 变更），当前为至少一次 + 消费幂等。
- `message.queue` / `notification.queue` 仍未挂 DLX（RabbitMQ 队列参数不可变，补挂需换新队列名并排空，收益是失败消息进 DLQ 而非依赖 consumed_event FAILED 留存，见第 9 节）。
- token 出源码、Gateway 头清洗等安全项属 P0-1，另行处理。

## 9. Message 与 AI 消费可靠性（P0-5 专项）

> 对应 `repair-plan.md` P0 第 5 节七项。核心思路：消费幂等从「Redis 先写标记」改为「数据库 consumed_event 状态机」，配合 AI 队列 v2 拓扑（DLX + 延迟重试 + 死信登记）。

### 9.1 AI 队列 v2 拓扑

```
ai.exchange (direct)
 ├─ ai.testcase.routing  → ai.testcase.queue (DLX=ai.dlx, DLK=ai.dead)
 ├─ ai.wa-advice.routing → ai.advice.queue   (DLX=ai.dlx, DLK=ai.dead)
 ├─ ai.testcase.wait.queue：无消费者，DLX=ai.exchange / DLK=ai.testcase.routing
 └─ ai.advice.wait.queue： 无消费者，DLX=ai.exchange / DLK=ai.wa-advice.routing
       消费失败 → per-message TTL(5s/10s/20s) → 到期弹回对应主队列
ai.dlx (direct) ── ai.dead ──→ ai.dead.queue
       └─ AiDeadLetterHandler：尽力提取 eventId，consumed_event 落 FAILED（不覆盖 COMPLETED）
```

- 常量与声明：`MqContexts` / `MqConfig`（service-common），`AI_TESTCASE_QUEUE`、`AI_ADVICE_QUEUE`、`AI_TESTCASE_WAIT_QUEUE`、`AI_ADVICE_WAIT_QUEUE`、`AI_DLX`、`AI_DLQ`。
- 消费入口：service-ai `MQ/Mq.java` 分发器（骨架照抄 `JudgeSubmitConsumer`）：毒消息（`IllegalArgumentException` 载荷解析失败）直接死信；业务异常读 `x-codewise-retry-count` 头指数退避，按 routing key 转投对应等待队列后 ACK 原消息；3 次超限死信。handler 只抛异常、不接触 Channel；会话校验等业务失败抛 `IllegalStateException` 走重试，`IllegalArgumentException` 仅保留毒消息语义。
- testcase 生成失败补偿（发「删除题目」消息）只在重试超限的终态尝试发送一次（handler 自读 `x-codewise-retry-count` 头判断），重试期间不补偿。
- **旧 `ai.queue` 已弃用**（参数不可变无法补挂 DLX，`Ai_QUEUE_NAME` 已 @Deprecated 不再声明）。

### 9.2 consumed_event 消费幂等状态机

两张同构表（各服务各库，禁止跨库）：

- `codewise_message.consumed_event`（DDL：`service-message/src/main/resources/sql.sql`），eventId = NotificationDto.messageId / EmailMessage.eventId。
- `codewise_ai.consumed_event`（DDL：`service-ai/src/main/resources/sql/consumed_event.sql`，多一列 `result_ref`），eventId = 信封 eventId（裸格式兜底 messageId）。

状态机与 claim 语义：

| 状态 | 含义 |
|------|------|
| PROCESSING | 已认领（INSERT 占位，`uk_event_id` 兜底幂等），业务未全部完成 |
| COMPLETED | 业务彻底完成（邮件已发出 / 推送成功 / AI 建议通知已发出），**唯一跳过态** |
| FAILED | 重试超限（死信登记）或业务主动放弃，终态留存供人工重放 |

- claim：INSERT 成功 = NEW；唯一键冲突按既有行分派：COMPLETED → 真重复直接 ACK；PROCESSING/FAILED → RECLAIM 重新接管（broker 对未 ACK 消息重投，崩溃残留行必须可重跑，at-least-once）。
- `recordFailure`：`retry_count = retry_count + 1` 原子自增、last_error 截断 500 字符，状态保持 PROCESSING。
- 覆盖消费者：service-message `EmailService`（email.routing）、`AiAdviceHandle`（notification.ai.advice.routing）；service-ai `WAAiHandle`（ai.wa-advice.routing）；service-review `ReviewService.setReview`（reviews.judge.record.routing，幂等键 `review:judge:{judgeRecordId}`，claim 行与 SM-2 业务更新同事务）。4 个通知 handler（like/review/checked/appeal）维持原有「业务成功后写标记 + DB 唯一键」安全模式。
- service-message `mq/Mq.java` 分发器对 handler 未捕获异常（如 DB 抖动导致 claim 失败）做兜底：退避 5s 后 nack 重投，消息不丢（这两个队列未挂 DLX，nack(requeue=false) 等于丢弃）。

### 9.3 关键丢失/提前完成风险的消除

- **邮件失败直接 ACK** → 失败 nack 重投立即重试，3 次超限 `markFailed` 留存后丢弃；日志只记收件人与主题，不落验证码正文。`EmailMessage` 新增 eventId，service-common 生产端 3 参构造自动生成 UUID；旧格式消息消费端生成一次性 UUID 兜底（仅靠 broker 重投语义）。
- **AI 建议通知失败被提前标记完成** → WAAiHandle：claim → 生成建议落库 → `markResultRef`（记 ai_message 主键）→ 发通知 → 成功才 `complete`。通知失败异常上抛交分发器延迟重试；重投命中 result_ref 非空时跳过生成、仅补发通知。Redis pending 标记已整体移除。
- **WA 建议重试的事件级防重**（避免 AI 故障期间重复副作用）：追问分支以事件首次认领时间（consumed_event.create_time）为界——该事件已落过 SYSTEM 追问时不再新增：上次占位行 FAILED/GENERATING → 复用该行重试生成（`restartFailedGeneration` 原子重置）；已 COMPLETED（result_ref 丢失窗口）→ 只补结果引用与通知，不再生成。每个事件至多一条 SYSTEM 追问与一条 ASSISTANT 占位行。引用悬空（行被删）→ markFailed 终态留痕。
- **ai_message 生成状态**：新增 `status` 列（`GENERATING/COMPLETED/FAILED/CANCELLED`，迁移脚本 `service-ai/src/main/resources/sql/migration_20260823_ai_message_status.sql`，存量 ASSISTANT 行回填 COMPLETED）。同步与 SSE 流式路径均先插 GENERATING 占位行，收尾一律 `WHERE status='GENERATING'` 原子更新：完成→COMPLETED，异常→FAILED（保留部分内容），超时/客户端断开→CANCELLED。SSE 超时只取消本流占位行（按 messageId 精准取消，不影响同会话并发流）。
- **SSE 错误脱敏**：`AiAdviceController#toUserMessage` 仅透出 `AiProviderHttpException` 分类文案（认证失败/限流/HTTP 状态码），其余异常返回固定文案「AI 服务暂时不可用，请稍后重试」，原始异常仅服务端日志留痕。

### 9.4 部署与排空步骤（AI v2 上线时）

1. 按顺序构建：`service-common install` → service-message / service-ai（同批发布；`EmailMessage` 加字段、AI 队列改名均要求同批）。
2. **先在 RabbitMQ 管理台删除旧 `ai.queue` 到 `ai.exchange` 的两条旧绑定**（ai.testcase.routing、ai.wa-advice.routing），再发布新版本——否则灰度期间新消息会同时投递旧队列（无消费者）造成堆积。
3. 执行 DDL：`codewise_message` 追加 `consumed_event`；`codewise_ai` 建 `consumed_event`（或执行主 DDL 增量部分）、执行 `migration_20260823_ai_message_status.sql`。
4. 旧 `ai.queue` 若仍有残留消息，管理台（15672）按消息路由键 moveTo `ai.advice.queue` / `ai.testcase.queue`，确认排空后删除旧队列。

### 9.5 人工重放（consumed_event FAILED 行）

```sql
-- 排查失败留存
SELECT event_id, routing_key, status, retry_count, last_error, update_time
FROM consumed_event WHERE status = 'FAILED' ORDER BY update_time DESC;

-- 重放：状态改回 PROCESSING 后，把原始消息重发到对应交换机/路由键
-- （或由生产方重发同一 eventId 的消息，消费端按 RECLAIM 重新接管）
UPDATE consumed_event SET status = 'PROCESSING', retry_count = 0 WHERE event_id = '<eventId>';
```

### 9.6 观测点（建议接入告警）

- 两库 `consumed_event` 中 `status='FAILED'` 或 PROCESSING 超过 5 分钟的行数。
- `ai.dead.queue` 深度 > 0；`ai.*.wait.queue` 深度持续增长（下游持续失败）。
- service-ai `ai_message` 中 GENERATING 超过 5 分钟的行（SSE 崩溃未收尾，onTimeout 兜底会转 CANCELLED）。

---

# 迭代手册（后续维护怎么做）

> 面向后续迭代者的操作手册：新增事件、新增消费者、日常运维、故障排查、升级路径。
> 总手册（构建顺序、发布检查）见 `docs/maintenance-guide.md`，本节只讲消息链路相关的增量纪律。

## A. 新增一个事件类型（标准五步）

以「新增 XXX 事件」为例：

1. **登记类型常量**：`service-api` `dto/event/EventTypes.java` 加常量。类型命名「领域_对象_动作」，先登记再引用，禁止魔法值散落。
2. **选发布方式**：
   - 需要与数据库写入原子一致 → 在业务 `@Transactional` 内 `outboxService.append(eventType, exchange, routingKey, payload)`；
   - 纯通知、允许丢 → `eventPublisher.publish(...)` 非事务直发。
   - payload 只放 ID 引用；新的大字段诉求先落库（或已有表），消费方按需 Feign 拉取。
3. **消费端**：`EnvelopeCodec.unwrap(body, X.class)` 双读；**必须实现幂等**（业务唯一键 CAS / `judge_status` 短路 / Redis setIfAbsent 三选一，参考现有消费者）。
4. **测试**：生产端验证 append/publish 参数（参考 `JudgeTaskServiceTest`）；消费端验证裸格式 + 信封格式双读与幂等分支（参考 `JudgeSubmitConsumerTest`）。
5. **文档**：本文档第 3 节的覆盖事件清单补一行。

## B. 新增消费者 / 新队列

1. `MqContexts` 加队列名常量（放对应分组，注明用途）。
2. `service-common` `MqConfig` 声明 Queue + Binding：**一律挂 DLX**（`deadLetterExchange(JUDGE_DLX).deadLetterRoutingKey(JUDGE_DEAD_ROUTING_KEY)`，或建新 DLX）。不挂 DLX 的队列 nack 即丢消息。
3. 队列名必须用新名字才能改参数——RabbitMQ 队列参数不可变，改参数（TTL/DLX/长度限制）= 换新队列 + 排空旧队列。
4. 消费者骨架照抄 `JudgeSubmitConsumer`：手动 ACK、毒消息（`IllegalArgumentException` 解析失败）nack 进 DLQ、业务异常按重试策略处理、日志带 eventId/retryCount/业务键/最终结果。
5. 消费者不写业务，业务下沉到 handler/Service（参考 handler 薄壳化改造）。

## C. 日常运维操作

### 消息卡住/失败的排查顺序

1. `SELECT status, COUNT(*) FROM codewise_question.outboxpro_outbox GROUP BY status;`——PENDING 积压=Relay 未跑或 broker 断；DEAD=投递超限（`last_error_message` 有原因）。
2. RabbitMQ 管理台（`127.0.0.1:15672`，仅宿主回环）看 `judge.submit.queue` 深度与 `judge.dead.queue` 是否有死信。
3. `SELECT * FROM failure_submit ORDER BY failure_submit_id DESC LIMIT 20;`——判题死信登记情况。

### 三条人工重放通道

| 通道 | 操作 | 适用 |
|------|------|------|
| 判题失败重试 | `POST /api/judge/failure/retry/{failureId}`（先 `GET /{status}/list` 查列表） | DLQ 死信登记的提交 |
| Outbox DEAD 重放 | `GET /actuator/outboxpro-ops/outbox`、`/dlq`（内部 Token，只读查询）；HTTP 重放未开放，必要时由 DBA 按 `outboxpro_dead_letter` 台账修复 | 投递超限事件 |
| 队列手动转移 | 管理台 move 消息到目标队列 | 特殊抢救，慎用 |

### 观测点（OutboxPro 已内置告警，建议再接入 Prometheus）

- 高水位告警：死信积压达 `outboxpro.dlq.alert.threshold`（默认 100）时 `OUTBOXPRO_ALERT` logger 输出 ERROR。
- Micrometer 计数器：`outboxpro.publish.*` / `outboxpro.consume.*` / `outboxpro.inbox.duplicate` /
  `codewise.outboxpro.deadletter`（tag reason）。
- `judge.dead.queue` 深度 > 0。
- `failure_submit` 中 status 长期非 SUCCESS 的记录。
- 消费日志中的 `RETRY`/`DEAD` 关键字。

## D. 升级路径（已排好的后续项）

1. ~~**开启 publisher confirm**~~ ✅ 已随 OutboxPro 迁移内置：Relay 经 Publisher Confirm 确认后才标 SENT。
2. **其余服务迁移到信封**：复习链路已迁移（question→review 走 Outbox 信封、review 消费与 message 的 ReviewHandle 均双读）；消息/社区等剩余链路继续逐个改 `EnvelopeCodec.unwrap` 双读 → 全量切换后可强制信封（unwrap 去掉裸格式分支，`schemaVersion` 校验加强）。
3. **payload 结构变更**：`schemaVersion` +1，消费端按版本分支兼容一个迭代周期后删旧分支。
4. **message/notification 队列补 DLX**：照抄 judge/AI 的「新队列名 + wait 队列 + DLQ」模式，将邮件/通知失败从「consumed_event FAILED 留存」升级为「死信进 DLQ 可管理台重放」（repair-plan 后续项）。
5. **consumed_event 模式推广**：4 个通知 handler（like/review/checked/appeal）如需统一到数据库幂等，复用 claim/complete/recordFailure/markFailed 骨架。
6. **消费端接入 OutboxPro RELIABLE**：短耗时 handler（通知中心、复习状态）可迁 `EventBinding.reliable`（Inbox 幂等替代三份 consumed_event 拷贝）；**judge/ai 长耗时消费（Docker 判题、LLM 调用）不可迁移**——RELIABLE 模式会把 handler 包进 DB 事务，连接被占数分钟。

## E. 修改共享模块的纪律

- 改 `service-api`/`service-common` 后必须先 `install` 再构建业务模块（命令见维护手册第 5 节），否则业务模块编译到旧契约。
- **新增自动配置的陷阱**：`org.example.*` 自定义自动配置按 FQN 排在 Spring 官方之前解析，条件里依赖 Spring 官方 Bean（如 `@ConditionalOnBean(RabbitTemplate.class)`）必须配 `@AutoConfigureAfter(...)`，否则条件永远为 false（本次已踩过，见 `EventAutoConfiguration` 注释）。
- `AiAdviceWADto` 这类跨服务 DTO 改字段 = 生产者与所有消费者同批发布，改前先 grep 全部使用点。
- 并行开发时共享常量（MqContexts/EventTypes/DTO）先行合入，业务模块再并行。
