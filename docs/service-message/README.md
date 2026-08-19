# service-message 消息模块说明

`service-message` 是 CodeWise 的统一消息服务，负责邮件、站内通知和 WebSocket 实时推送。原 `service-email` 不再独立维护，邮件能力已合并到本模块。

## 模块职责

- 消费邮件、点赞通知和复习提醒消息。
- 将通知持久化到 `codewise_message.notification_center`。
- 提供通知列表、详情、未读数、已读和删除接口。
- 通过 STOMP/WebSocket 向在线用户实时推送通知。
- 使用 Redis 和数据库唯一索引处理重复消费。

业务服务只负责产生通知事件，不直接写消息库，也不依赖 WebSocket 在线状态。

## 包结构

```text
org.example.servicemessage
|-- config/                         # 异步线程池和 Web 拦截配置
|-- email/emailService/             # 邮件发送
|-- mq/                             # 队列监听与 routing key 分派
|-- notificationcenter/
|   |-- controller/                 # 通知中心 HTTP 接口
|   |-- entry/                      # notification_center 实体
|   |-- handle/                     # 点赞与复习通知消费者
|   |-- mapper/                     # MyBatis-Plus Mapper
|   |-- service/                    # 查询、已读和软删除逻辑
|   `-- vo/                         # 列表摘要、详情和游标响应
`-- websocket/
    |-- websocketConfig/            # STOMP 端点与握手鉴权
    `-- websocketService/           # 用户队列和广播推送
```

## MQ 拓扑

通知中心使用独立交换机和队列：

```text
exchange: notification.exchange
queue: notification.queue

notification.like.routing   -> NotificationCenterLikeHandle
notification.review.routing -> ReviewHandle
```

消费者根据 `RECEIVED_ROUTING_KEY` 查找对应 `MessageHandler`。消息格式错误时拒绝且不重新入队；业务处理失败最多重试 3 次，失败信息记录到 Redis。

## 幂等与推送顺序

```text
消费 NotificationDto
  -> 校验 messageId、接收用户和扩展数据
  -> 检查 Redis 短期幂等标记
  -> 插入 notification_center
  -> 数据库 uk_message_id 兜底防重
  -> 写入 Redis 成功标记
  -> 尝试 WebSocket 推送
  -> ACK
```

通知先入库再实时推送。WebSocket 推送失败不会回滚数据库通知，也不会触发重复入库；用户稍后仍可通过 HTTP 接口读取站内通知。

点赞通知的 `messageId` 由业务类型、点赞用户和目标 ID 组成，同一用户取消后再次点赞不会重复生成站内通知。复习提醒按用户、日期和提醒场景生成固定 ID，上午未创建计划与晚间未完成计划互不冲突。

## 通知中心接口

统一通过网关访问：`http://localhost:8082/api/message/notifications`。

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
- `type`：可选 `LIKE` 或 `REVIEW`。
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

完整建表脚本见 `service-message/src/main/resources/sql.sql`。

## WebSocket

STOMP 端点：

```text
/websocket
```

网关使用独立的 `lb:ws://service-message` 路由转发 `/websocket/**`。握手阶段优先读取网关透传的 `X-User-Id`，也支持从查询参数 `token` 或 `Authorization: Bearer ...` 解析 JWT。

消息前缀：

```text
/topic   广播
/queue   队列
/app     客户端发送到服务端
/user    用户目标前缀
```

`WebSocketPushService` 提供：

- `pushToTopic(topic, payload)`：广播消息。
- `pushToUserQueue(userId, queueName, payload)`：向指定用户推送。

## 邮件

`EmailService` 处理注册验证码、找回密码验证码和普通系统邮件。验证码内容以 HTML 模板发送，其他内容按纯文本发送。新邮件场景继续扩展本模块，不再新增 `service-email`。

## 当前边界

- RabbitMQ 消费端已有数据库幂等和有限重试。
- WebSocket 失败不会影响站内信持久化。
- AI 建议是题目侧边栏的瞬时推送，不写入通知中心收件箱；完整回答由 `service-ai` 自己持久化。
- AI 建议使用独立 `AI_ADVICE` 用户队列，并用 Redis 事件键避免重复推送。
- 生产端尚未完整接入 publisher confirm、Outbox 和死信补偿。
- Redis 失败记录目前需要人工或后续补偿任务处理。
