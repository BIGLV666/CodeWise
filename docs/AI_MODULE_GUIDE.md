# CodeWise AI 模块说明

## 1. 当前完成范围

当前 AI MVP 已形成三个闭环：

1. WA、RE、TLE 等判题失败后异步生成建议，先入库，再通过消息服务 WebSocket 推送。
2. 用户在题目侧边栏追问，后端通过 POST SSE 流式返回模型文本，完成后保存消息。
3. 每次回答落库后异步检查会话长度，由 Ollama 小模型增量压缩或重建会话记忆。

会话、消息和摘要以 MySQL 为事实来源。Redis 不缓存摘要，只负责 MQ 幂等和 Redisson 会话级摘要锁。长期用户画像、跨题记忆和代码 diff 暂未实现。

## 2. 模块职责

```text
service-ai/src/main/java/org/example/serviceai
├── controller
│   └── AiAdviceController              会话查询、消息分页和 SSE 追问
├── conversation
│   ├── dto                              AskDto 与游标分页结果
│   ├── enums                            USER / ASSISTANT / SYSTEM
│   ├── repository                       会话存储接口及 MyBatis 实现
│   ├── service
│   │   ├── AdviceConversationService    问答编排与消息落库
│   │   ├── AdvicePromptBuilder          首次建议和追问 Prompt
│   │   └── AiConversationMemaryService  摘要更新、重建与锁
│   └── vo                               题目会话索引
├── entry                                Conversation / Message / Memory
├── mapper                               MyBatis-Plus Mapper
├── handle/testcasehandle
│   └── WAAiHandle                       自动判题建议消费者
└── service                              AIService 与模型适配器
```

Prompt 构建、模型调用、持久化和推送分别放在对应层，不由 Controller 或 Mapper 混合承担。

## 3. 自动建议链路

```text
service-judge 判题失败
  -> RabbitMQ AI 事件
  -> WAAiHandle
  -> 创建或查找用户/题目的根会话
  -> 云端模型生成建议
  -> 保存 ASSISTANT Message
  -> RabbitMQ AI_ADVICE 通知事件
  -> service-message / AiAdviceHandle
  -> WebSocket AI_ADVICE 用户队列
```

自动建议先入库再推送。WebSocket 允许丢失，但用户重新打开会话时仍能从数据库读取回答。AI 建议不进入通知中心收件箱。

## 4. 用户追问与 SSE

```http
POST /api/ai/advice/ask
Content-Type: application/json
Accept: text/event-stream

{
  "conversationId": 12,
  "question": "为什么这里会越界？",
  "code": "当前代码"
}
```

事件协议：

```text
event: chunk
data: 模型增量文本

event: answer
data: 已写入 ai_message 的完整 Message

event: done
data: [DONE]
```

异常使用 `error` 事件。接口为 POST SSE，前端使用 `fetch()` 读取响应流，不能直接使用只支持 GET 的原生 `EventSource`。

模型已经输出任意 chunk 后，策略层不再切换 Provider，避免把两个模型的回答拼接到同一条消息。首个 Provider 尚未输出内容时才允许降级。

## 5. Prompt 上下文顺序

首次自动建议包含根题目、提交代码、语言、判题状态、日志以及失败输入输出。

后续追问按以下顺序组装：

```text
根题目与首次判题上下文
+ 会话记忆摘要
+ 最近 6 条原始消息
+ 本次问题
+ 当前代码
```

根上下文是事实基础，不参与摘要替换。摘要和历史可能过时；若与最近提交、当前代码或本次问题冲突，以最新信息为准。Prompt 使用字符预算限制题目、代码、日志、单条消息和总长度。

## 6. 会话记忆

每个 `(conversation_id, user_id)` 对应一条 `ai_conversation_memory`：

| 字段 | 用途 |
| --- | --- |
| `summary` | 结构化 JSON 摘要 |
| `summary_chars_count` | 重建阈值判断 |
| `end_message_id` | 摘要已覆盖到的消息 |
| `version` | MyBatis-Plus 乐观锁 |

回答消息落库后，通过异步任务调用记忆服务：

```text
获取 conversationId 级 Redisson 锁
  -> 未压缩消息不足 14 条：结束
  -> 无摘要：创建初始摘要
  -> 摘要小于 3000 字符：增量压缩
  -> 摘要达到 3000 字符：后续任务执行重建
```

重建固定保留最近 4 条 `SYSTEM` 提交事件，并与根题目、旧摘要和近期普通对话一起交给 Ollama。原始消息不删除；压缩失败或乐观锁更新失败时保留旧摘要，等待下一次异步任务继续尝试。

## 7. 模型职责

| 模型类型 | 当前职责 |
| --- | --- |
| 云端模型 | 自动判题建议、用户实时追问、主要回答质量 |
| Ollama 小模型 | 会话摘要、未来记忆整理、无输出时的最终兜底 |

不同模型统一实现 `CallAi`。`AIServiceManager` 按优先级选择健康 Provider，记录失败次数并进行切换；`AIService` 记录 Provider 名称、耗时、超时判断和根因。

## 8. 数据库与迁移

数据库名：`codewise_ai`。

完整建表：

```text
service-ai/src/main/resources/sql/codewise_ai.sql
```

主要约束：

```sql
UNIQUE KEY uk_ai_conversation_user_question (user_id, question_id);
KEY idx_ai_message_conversation_cursor (conversation_id, message_id);
UNIQUE KEY uk_ai_memory_conversation_user (conversation_id, user_id);
```

已有数据库按 `service-ai/src/main/resources/sql/migration_*.sql` 顺序执行迁移。Nacos 的 `service-ai.yaml` 数据源需要指向 `codewise_ai`。

## 9. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/ai/advice` | 查询当前用户所有题目根会话 |
| `POST` | `/api/ai/advice/ask` | SSE 流式追问 |
| `GET` | `/api/ai/advice/{conversationId}/messages` | 按 `messageId` 游标分页 |

消息分页从数据库倒序查询，返回前恢复为时间正序。根 `Conversation` 只提供题目与首次提交上下文，不作为消息返回。

## 10. ID 语义

| ID | 用途 |
| --- | --- |
| `eventId` | MQ 消费与 WebSocket 推送幂等 |
| `conversationId` | 用户与题目的根会话 |
| `messageId` | 数据库消息、排序和前端去重 |
| `submitId` | 判题提交事件及未来代码 diff |

不要让 MQ `messageId`、数据库 `messageId` 和 `conversationId` 互相替代。

## 11. 本地验证

1. 执行完整建表或增量迁移。
2. 启动 MySQL、Redis、RabbitMQ、Nacos、Ollama 与相关微服务。
3. 提交 WA 代码，确认会话和建议先入库，再收到 WebSocket `AI_ADVICE`。
4. 调用 `/ask`，确认连续收到 `chunk`，最后收到带数据库 ID 的 `answer` 和 `done`。
5. 连续追问超过摘要阈值，确认 `ai_conversation_memory` 更新 `end_message_id` 和 `version`。
6. 使用游标连续读取两页消息，确认无重复、无遗漏且页内时间正序。

## 12. 完成边界与后续方向

当前 AI MVP 到此收尾，不增加 Redis 摘要缓存。下一阶段优先实现力扣式核心函数判题模式，让平台产生稳定的真实提交数据；之后再按顺序推进相邻提交 diff 和跨题长期记忆。
