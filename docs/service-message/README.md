# service-message 消息模块说明

`service-message` 是 CodeWise 的统一消息服务，负责邮件、站内通知和 WebSocket 实时推送。原 `service-email` 不再独立维护，邮件能力已合并到本模块。

## 模块职责

- 消费邮件、判题/调试结果 WebSocket 转发消息，以及站内通知（点赞、复习提醒、复习掌握祝贺、内容审核、申诉、AI 建议）。
- 将站内通知持久化到 `codewise_message.notification_center`。
- 提供通知列表、详情、未读数、已读和删除接口。
- 通过 STOMP/WebSocket 向在线用户实时推送通知和判题结果。
- 使用三层幂等处理重复消费：Redis 短期幂等标记、数据库唯一索引（`notification_center.uk_message_id`）、`consumed_event` 表状态机（邮件 / AI 建议 / 复习掌握）。

业务服务只负责产生通知事件，不直接写消息库，也不依赖 WebSocket 在线状态。

## 包结构

```text
org.example.servicemessage
|-- Advice/                         # 全局异常处理：GlobalExceptionHandler 继承 service-common 的 AbstractGlobalExceptionHandler，只声明 basePackages（notificationcenter.controller）；错误码见 exception/MessageErrorCode（服务号 05）
|-- config/                         # 异步线程池和 Web 拦截配置
|-- consumedevent/                  # consumed_event 表：数据库幂等与消费状态机
|-- email/emailService/             # 邮件发送消费者
|-- mq/                             # 队列监听与 routing key 分派
|-- notificationcenter/
|   |-- controller/                 # 通知中心 HTTP 接口
|   |-- entry/                      # notification_center 实体
|   |-- handle/                     # 六类通知消费者（点赞/复习/掌握/审核/申诉/AI 建议）
|   |-- mapper/                     # MyBatis-Plus Mapper
|   |-- service/                    # 查询、已读和软删除逻辑
|   `-- vo/                         # 列表摘要、详情和游标响应
`-- websocket/
    |-- websocketConfig/            # STOMP 端点与握手鉴权
    `-- websocketService/           # 用户队列和广播推送
```

## MQ 拓扑

`Mq` 消费者同时监听两个队列，根据 `RECEIVED_ROUTING_KEY` 查找对应 `MessageHandler`；找不到处理器或消息格式错误时 nack 且不再入队，处理器自身 try/catch 之外的基础设施异常退避 5 秒后 nack 重投（`Mq.java`）。两个队列均未挂 DLX，超限丢弃的失败信息由 Redis 或 `consumed_event` 表留存。常量取自 `service-common` 的 `MqContexts`：

```text
message.exchange（MESSAGE_EXCHANGE，direct）-> message.queue（MESSAGE_QUEUE_NAME）
  email.routing（MESSAGE_ROUTING_KEY）        -> EmailService（邮件发送）
  websocket.routing（WEBSOCKET_ROUTING_KEY）  -> WebSocketPushService（转发判题/调试结果、AI 建议任务进度，由 service-question、service-ai 发布）

notification.exchange（NOTIFICATION_EXCHANGE，direct）-> notification.queue（NOTIFICATION_QUEUE_NAME）
  notification.like.routing            -> NotificationCenterLikeHandle
  notification.review.routing          -> ReviewHandle（EnvelopeCodec 信封/裸格式双读）
  notification.review.mastered.routing -> ReviewMasteredHandle（经 Outbox，EnvelopeCodec 双读）
  notification.checked.routing         -> CheckedHandle（社区内容审核结果）
  notification.appeal.routing          -> AppealHandle（下架申诉处理结果，经 Outbox）
  notification.ai.advice.routing       -> AiAdviceHandle（仅 WebSocket 推送，不写收件箱）
```

重试语义分两类：点赞/复习/审核/申诉处理器用 Redis（`MQ_*_RETRY_COUNT_KEY` 计数，超限写入 `MQ_*_FAILED_KEY` 留存）最多立即重试 3 次；邮件/AI 建议/复习掌握处理器用 `consumed_event` 表（PROCESSING → COMPLETED/FAILED，`retry_count` 计数）最多重试 3 次，超限置 FAILED 留存供人工重放（把该行 status 改回 PROCESSING 后由生产方重发）。

## 幂等与推送顺序

```text
消费 NotificationDto（点赞/复习/审核/申诉）
  -> 校验 messageId、接收用户和扩展数据
  -> 检查 Redis 短期幂等标记
  -> 插入 notification_center
  -> 数据库 uk_message_id 兜底防重（DuplicateKeyException 视为成功）
  -> 写入 Redis 成功标记（7 天）
  -> 尝试 WebSocket 推送（点赞当前不推送，仅入库）
  -> ACK
```

通知先入库再实时推送。WebSocket 推送失败不会回滚数据库通知，也不会触发重复入库；用户稍后仍可通过 HTTP 接口读取站内通知。数据库提交记录（`notification_center` / 判题 submit 记录）才是真相来源，WebSocket 只是 best-effort。

点赞通知的 `messageId` 形如 `like:{业务类型}:{点赞用户}:{目标ID}`（`LikeRecordService`），同一用户取消后再次点赞不会重复生成站内通知。复习提醒按 `review-reminder:{场景}:{用户}:{日期}` 生成固定 ID（`ReviewMessageTask`），上午未创建计划（`not_record`）与晚间未完成计划（`have_record`）互不冲突。复习掌握祝贺以终态 `review:mastered:{用户}:{题目}` 天然唯一（`ReviewService`）。

## 通知中心接口

服务自身监听 `8083` 端口，控制器基础路径 `/api/message/notifications`，统一通过网关访问：`http://localhost:8082/api/message/notifications`（网关路由 `/api/message/**`）。鉴权走全局 `UserAuthInterceptor`：仅接受网关注入的 `X-Internal-Token`/`X-User-Id`，调用方一律从 `UserContext` 取，不接受客户端传参；控制器带 `@RateLimit(limit = 300, window = 60)`（Redis 滑动窗口，超限返回 429）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/message/notifications` | 倒序游标分页查询通知摘要 |
| `GET` | `/api/message/notifications/unread-count` | 查询未删除通知的未读数 |
| `GET` | `/api/message/notifications/{notificationId}` | 查询详情并自动标记已读 |
| `PUT` | `/api/message/notifications/{notificationId}/read` | 单独标记一条通知已读 |
| `PUT` | `/api/message/notifications/read-all` | 全部已读，可按通知类型过滤 |
| `DELETE` | `/api/message/notifications/{notificationId}` | 软删除自己的通知 |

列表参数：

- `lastId`：首次不传，后续传上次响应中的 `nextCursor`。
- `pageSize`：默认 20，范围 1 到 100。
- `type`：可选 `NotificationCenterType` 枚举值：`LIKE`、`REVIEW`、`CHECKED`、`APPEAL`、`REVIEW_MASTERED`（`AI_ADVICE` 不入库，收件箱中不会出现）。
- `unreadOnly`：传 `true` 时仅查询未读通知。

列表只返回标题、类型、业务 ID、已读状态和创建时间。正文与 `extraData` 仅在详情接口返回，`extraData` 已解析为 JSON 对象。返回前端的通知 ID、用户 ID、业务 ID 和下一页游标均使用字符串，避免 JavaScript 整数精度丢失。

## 数据表

数据库：`codewise_message`

核心表：`notification_center`

关键约束和索引：

- `uk_message_id(message_id)`：消费幂等最终兜底。
- `idx_user_status_time(user_id, is_deleted, is_read, create_time)`：用户收件箱与未读查询。
- `idx_business(business_type, business_id)`：按关联业务定位通知。
- `is_deleted`：软删除标记，不物理删除消息。

辅助表：`consumed_event`（邮件 / AI 建议 / 复习掌握的数据库幂等）——`uk_event_id(event_id)` 唯一幂等键，`status`（PROCESSING/COMPLETED/FAILED）、`retry_count` 原子自增计数、`last_error` 截断 500 字符留存，`idx_status` 供人工核查失败留存。

完整建表脚本见 `service-message/src/main/resources/sql.sql`。

## WebSocket

WebSocket 握手除 JWT 解析外还必须校验网关注入的 `X-Internal-Token`；直接携带伪造身份头访问下游服务会被拒绝。

STOMP 端点：

```text
/websocket
```

网关使用独立的 `lb:ws://service-message` 路由转发 `/websocket/**`（并在 `WebConfig` 中排除该路径的 HTTP 拦截器）。握手阶段 `WebSocketAuthInterceptor` 先比对 `X-Internal-Token`，再优先读取网关透传的 `X-User-Id`，最后回退到查询参数 `token` 或 `Authorization: Bearer ...` 解析 JWT。

消息前缀：

```text
/topic   广播
/queue   队列
/app     客户端发送到服务端
/user    用户目标前缀（已配置，当前服务端推送未使用）
```

`WebSocketPushService` 提供：

- `pushToTopic(topic, payload)`：广播消息。
- `pushToUserQueue(userId, queueName, payload)`：向指定用户推送，实际目的地为 `/queue/{queueName}-{userId}`，客户端需订阅该地址。

队列名来自枚举 `WebSocketQueueName`：`INBOX_REVIEW`、`INBOX_CHECKED`、`INBOX_APPEAL`、`AI_ADVICE`（`INBOX_LIKE` 预留，当前点赞不推送）。经 `websocket.routing` 转发的判题/调试结果使用 `WebsocketContexts` 中的 `judge-result`、`debug-judge-result`。

## 邮件

`EmailService` 消费 `email.routing`，处理注册验证码、找回密码验证码和普通系统邮件。正文含“验证码”的以 HTML 模板发送（提取 4–8 位验证码渲染卡片），正文以 `<!doctype html`/`<html` 开头的原样按 HTML 发送，其余按纯文本发送。发件人地址取 `codewise.mail.from`（默认 `379299583@qq.com`）；SMTP 配置（`spring.mail.*`）经 Nacos `service-email.yaml`/`service-message.yaml` 下发，未配置 `spring.mail.host` 时 `JavaMailSender` 缺失、服务照常启动，邮件降级为失败留存。以 `EmailMessage.eventId` 走 `consumed_event` 幂等，旧格式无 eventId 的消息生成一次性 UUID 兜底。新邮件场景继续扩展本模块，不再新增 `service-email`。

## 配置与环境变量

- `server.port: 8083`。
- 必需环境变量：`JWT_SECRET`（握手 JWT 解析）、`CODEWISE_INTERNAL_TOKEN`（映射到 `codewise.internal-token`，网关注入头校验）、`NACOS_ADDR`（配置/注册中心，默认 `localhost:8848`）；SMTP 凭据经 Nacos 下发。
- `spring.rabbitmq.listener.simple.acknowledge-mode: manual` 必须显式声明——所有处理器手动 ACK/NACK，默认 AUTO 会双重确认导致 channel 崩溃。
- 本服务只消费不生产事件，显式 `outboxpro.enabled: false`。
- `api.governance`：接口日志、慢查询阈值 500ms、Redis 滑动窗口限流（超限 429，提示“通知操作过于频繁，请稍后重试”）。

## 当前边界

- RabbitMQ 消费端已有 Redis/数据库双重幂等和最多 3 次的有限重试。
- WebSocket 失败不会影响站内信持久化；判题结果以 submit 记录为准，推送仅 best-effort。
- AI 建议是题目侧边栏的瞬时推送，不写入通知中心收件箱；完整回答由 `service-ai` 自己持久化。
- AI 建议使用独立 `AI_ADVICE` 用户队列，并以 `consumed_event` 表（messageId 作 event_id）避免重复推送。
- 生产端接入程度不一：复习提醒/掌握祝贺（service-review）与申诉（service-community）已经过 OutboxPro 事务性发布，内容审核（service-community `PostCheckService`）仍是事务外裸 `RabbitTemplate` 发送；`message.queue`/`notification.queue` 均未挂 DLX，超限消息直接丢弃。
- 失败留存（Redis `MQ_*_FAILED_KEY` 或 `consumed_event` FAILED 行）目前需要人工核查或重放，暂无自动补偿任务。
