# 消息可靠性改造说明（P1 消息与事务专项）

> 对应 `docs/maintenance/repair-plan.md` P1「消息与事务」条目的实现说明。
> 范围：Transactional Outbox、统一消息信封、延迟重试/指数退避/DLQ/人工重放、大字段出 MQ、统一 Feign 拦截器、Judge 消费者拆分、JudgeService 拆分。

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
- 约定：payload 只放 ID 引用；代码、日志、输入输出等 LONGTEXT 大字段一律落库（judge_record），需要方经 `QuestionFeignClient#getJudgeContext(judgeRecordId)` 按需拉取。WebSocket 推送的 JudgeResultDto 保留字段形状，但 code/log/expectedOutput/actual 截断至 16KB。

## 3. Transactional Outbox

- 表：`event_outbox`（codewise_question 库，`service-question/src/main/resources/sql/event_outbox.sql`）。question 与 judge 共库共表，两侧写入。
- 写入：`OutboxService.append(...)` 必须在业务 `@Transactional` 内调用，事件行与业务写同事务提交，消除「DB 提交但消息未发」与「消息已发但 DB 回滚」。
- 投递：`OutboxRelay` 每秒批量认领（`FOR UPDATE SKIP LOCKED`，多实例/多服务安全），失败指数退避 `10s * 2^n`，8 次后转 DEAD。
- 启用：服务 yaml `codewise.outbox.enabled: true`（question 与 judge 均已启用；relay 默认启用，开关 `codewise.outbox.relay.enabled`）。
- 覆盖事件：
  - question：JUDGE_SUBMIT_REQUEST（提交判题）、JUDGE_DEBUG_REQUEST（调试）
  - judge：JUDGE_RESULT_CALLBACK（结果回调 question）、AI_ADVICE_REQUEST（AI 建议，WA/RE/TLE 时）
- 当前以「发送不抛异常」为成功（至少一次）；后续 Nacos 开启 `publisher-confirm-type: correlated` 后可升级为 confirm 确认再标 SENT（仅改 OutboxRelay）。

## 4. 重试 / DLQ / 人工重放

| 通道 | 策略 | 人工重放 |
|------|------|----------|
| 消费失败（submit） | 延迟重试 5s/10s/20s（wait 队列 TTL 弹回），3 次后 nack 进 DLQ | DLQ → failure_submit 登记 → `POST /api/judge/failure/retry/{id}` |
| 消费失败（retry 流程） | 业务已记录 failure_submit=FAILURE，nack 进 DLQ 留痕 | 同上 |
| 消费失败（debug） | 写 Redis 错误结果 + 回调 question + ACK（前端可见失败原因） | 无需 |
| Outbox 投递失败 | 指数退避 10s*2^n，8 次转 DEAD | `GET /api/question/outbox/dead` + `POST /api/question/outbox/replay/{outboxId}`（管理员） |

毒消息（载荷解析失败）直接 nack 进 DLQ，不重试。

## 5. 消费日志约定

新消费者统一输出：eventId、eventType、routingKey、retryCount、业务键（submitId/uuid/failureId）、最终结果（ACK/RETRY/DEAD）。

## 6. 统一 Feign 拦截器

`service-common` `FeignHeaderAutoConfiguration` 提供全局 `commonFeignRequestInterceptor`：透传 X-User-Id/X-User-Name/X-Real-IP（来自 UserContext），注入 X-Internal-Token（读 `codewise.internal-token`，默认 `codewise-secret-2026` 与 UserAuthInterceptor 现行校验一致）。ai/community/judge/question/review 五份本地副本已删除。

## 7. 相关配置项速查

| 配置 | 默认 | 说明 |
|------|------|------|
| `codewise.outbox.enabled` | false | 启用 Outbox 组件（question/judge 已显式 true） |
| `codewise.outbox.relay.enabled` | true | 启用定时投递器 |
| `codewise.internal-token` | codewise-secret-2026 | 统一 Feign 拦截器内部 Token |
| `codewise.mq.enabled` | - | 既有开关，控制 MqConfig 声明 |

## 8. 已知限制与后续项

- publisher confirm 未启用（依赖 Nacos 变更），当前为至少一次 + 消费幂等。
- service-ai 的 `ai.queue` 仍未挂 DLX/重试（repair-plan P0「Message 与 AI 消费可靠性」独立条目，未在本专项范围）。
- token 出源码、Gateway 头清洗等安全项属 P0-1，另行处理。

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

1. `SELECT status, COUNT(*) FROM codewise_question.event_outbox GROUP BY status;`——PENDING 积压=Relay 未跑或 broker 断；DEAD=投递超限。
2. RabbitMQ 管理台看 `judge.submit.queue` 深度与 `judge.dead.queue` 是否有死信。
3. `SELECT * FROM failure_submit ORDER BY failure_submit_id DESC LIMIT 20;`——判题死信登记情况。

### 三条人工重放通道

| 通道 | 操作 | 适用 |
|------|------|------|
| 判题失败重试 | `POST /api/judge/failure/retry/{failureId}`（先 `GET /{status}/list` 查列表） | DLQ 死信登记的提交 |
| Outbox DEAD 重放 | `GET /api/question/outbox/dead` → `POST /api/question/outbox/replay/{outboxId}`（管理员） | 投递超限事件，重置 PENDING |
| 队列手动转移 | 管理台（15672）move 消息到目标队列 | 特殊抢救，慎用 |

### 观测点（建议接入告警）

- `event_outbox` 中 `status='DEAD'` 或 PENDING 超 1 分钟的行数。
- `judge.dead.queue` 深度 > 0。
- `failure_submit` 中 status 长期非 SUCCESS 的记录。
- 消费日志中的 `RETRY`/`DEAD` 关键字。

## D. 升级路径（已排好的后续项）

1. **开启 publisher confirm**：Nacos 加 `spring.rabbitmq.publisher-confirm-type: correlated` → 改 `OutboxRelay#publishOne` 为 confirm 回调成功后才标 SENT（仅改 common 一处，调用方无感）。
2. **其余服务迁移到信封**：消息/复习/社区等链路逐个改 `EnvelopeCodec.unwrap` 双读 → 全量切换后可强制信封（unwrap 去掉裸格式分支，`schemaVersion` 校验加强）。
3. **payload 结构变更**：`schemaVersion` +1，消费端按版本分支兼容一个迭代周期后删旧分支。
4. **ai.queue 补 DLX/重试**：照抄 judge 三队列模式（repair-plan P0 项）。

## E. 修改共享模块的纪律

- 改 `service-api`/`service-common` 后必须先 `install` 再构建业务模块（命令见维护手册第 5 节），否则业务模块编译到旧契约。
- **新增自动配置的陷阱**：`org.example.*` 自定义自动配置按 FQN 排在 Spring 官方之前解析，条件里依赖 Spring 官方 Bean（如 `@ConditionalOnBean(RabbitTemplate.class)`）必须配 `@AutoConfigureAfter(...)`，否则条件永远为 false（本次已踩过，见 `EventAutoConfiguration` 注释）。
- `AiAdviceWADto` 这类跨服务 DTO 改字段 = 生产者与所有消费者同批发布，改前先 grep 全部使用点。
- 并行开发时共享常量（MqContexts/EventTypes/DTO）先行合入，业务模块再并行。
