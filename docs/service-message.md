# service-message 消息模块说明

`service-message` 是 CodeWise 的统一消息模块，后续用于承载邮件、WebSocket 推送和站内通知等能力。

## 模块定位

原 `service-email` 不再作为独立模块维护，邮件能力合并到 `service-message`。

当前模块职责：

- 消费邮件 MQ 消息并发送邮件。
- 提供 WebSocket/STOMP 连接配置。
- 提供通用 WebSocket 推送服务。
- 作为后续站内通知、系统消息、判题结果推送收口点。

## 当前包结构

```text
org.example.servicemessage
├── ServiceMessageApplication
├── email
│   └── emailService
│       └── EmailService
├── mq
│   ├── MessageHandler
│   └── Mq
└── websocket
    ├── websocketConfig
    │   └── WebSocketConfig
    └── websocketService
        └── WebSocketPushService
```

## 配置

服务名：

```yaml
spring:
  application:
    name: service-message
```

Nacos 配置建议使用：

```text
service-message.yaml
```

为了迁移兼容，可以临时保留旧的：

```text
service-email.yaml
```

## 邮件能力

邮件发送由 `EmailService` 负责，监听公共 MQ 中的邮件队列。

典型用途：

- 注册验证码。
- 找回密码验证码。
- 系统邮件通知。

后续新增邮件场景时，不应再新建 `service-email`，直接扩展 `service-message`。

## WebSocket 能力

WebSocket 配置提供 STOMP 端点：

```text
/websocket
```

消息代理前缀：

```text
/topic
/queue
```

应用消息前缀：

```text
/app
```

用户目标前缀：

```text
/user
```

`WebSocketPushService` 提供两类推送：

- `pushToTopic(topic, payload)`：广播主题消息。
- `pushToUserQueue(userId, queueName, payload)`：向用户队列推送消息。

## 后续迁移建议

- 判题结果推送逐步迁移到 `service-message`。
- 复习结果推送逐步迁移到 `service-message`。
- 用户通知、系统公告、站内信统一放到 `service-message`。
- 其它业务模块只负责产生通知事件，不直接持有具体推送实现。
