# service-ai AI 服务说明与接口

## 1. 当前完成范围

当前 AI MVP 已形成三个闭环：

1. WA、RE、TLE 等判题失败后异步生成建议，先入库，再通过消息服务 WebSocket 推送。消费幂等与进度由 `consumed_event` 状态机（`PROCESSING/COMPLETED/FAILED`）在数据库落底，通知发送成功前事件绝不定为 COMPLETED。
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
├── entry                                Conversation / Message / MessageStatus / ConsumedEvent
├── mapper                               MyBatis-Plus Mapper
├── MQ
│   ├── Mq                              AI 双队列分发器（延迟重试 / 死信分派）
│   └── handler/AiDeadLetterHandler      死信登记（consumed_event 置 FAILED）
├── handle/testcasehandle
│   └── WAAiHandle                       自动判题建议消费者（事件状态机）
└── service                              AIService、ConsumedEventService 与模型适配器
```

Prompt 构建、模型调用、持久化和推送分别放在对应层，不由 Controller 或 Mapper 混合承担。

## 3. 自动建议链路

```text
service-judge 判题失败
  -> Outbox 事件（信封 eventId）→ ai.exchange / ai.wa-advice.routing
  -> ai.advice.queue（挂 ai.dlx，消费失败按 5s/10s/20s 延迟重试，3 次超限死信 ai.dead.queue）
  -> WAAiHandle：consumed_event 认领（eventId 幂等）
  -> 创建或查找用户/题目的根会话
  -> 云端模型生成建议
  -> 保存 ASSISTANT Message，并记 result_ref
  -> RabbitMQ AI_ADVICE 通知事件（发送成功才把事件置 COMPLETED）
  -> service-message / AiAdviceHandle
  -> WebSocket AI_ADVICE 用户队列
```

自动建议先入库再推送。通知发送失败时事件保持非 COMPLETED，由 ai.advice.wait.queue 延迟重试；重试命中 result_ref 时只补发通知、不重复生成。重试超限进入 `ai.dead.queue`，`AiDeadLetterHandler` 把事件置 FAILED 留存供人工重放（见 `docs/maintenance/messaging-reliability.md` 第 9 节）。WebSocket 允许丢失，但用户重新打开会话时仍能从数据库读取回答。AI 建议不进入通知中心收件箱。

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

event: error
data: 对用户安全的固定文案
```

异常使用 `error` 事件，文案已脱敏：仅 `AiProviderHttpException` 透出分类提示（认证失败 / 请求过于频繁 / HTTP 状态码），其余一律返回「AI 服务暂时不可用，请稍后重试」，原始异常只在服务端日志留痕。接口为 POST SSE，前端使用 `fetch()` 读取响应流，不能直接使用只支持 GET 的原生 `EventSource`。

流式生成前先落一条 `status=GENERATING` 的 ASSISTANT 占位消息，收尾按结果原子更新（见第 8 节状态表）；连接超时会触发 `onTimeout`，把仍在生成的消息置为 CANCELLED。

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

不同模型统一实现 `CallAi`。`AIServiceManager` 按优先级选择健康 Provider，记录失败次数并进行切换；后台单线程定时执行真实探测，健康实例每三分钟探测一次，不健康实例每三十秒复检，关闭服务时同步停止探测线程。`AIService` 记录 Provider 名称、耗时、超时判断和根因。

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

已有数据库按 `service-ai/src/main/resources/sql/migration_*.sql` 顺序执行迁移（当前：`migration_20260823_ai_message_status.sql` 为 ai_message 增加 status 列并把存量 ASSISTANT 行回填 COMPLETED；`consumed_event.sql` 建消费幂等状态表）。Nacos 的 `service-ai.yaml` 数据源需要指向 `codewise_ai`。

ASSISTANT 消息的生成状态（`ai_message.status`，USER/SYSTEM 行为 NULL）：

| 状态 | 含义 |
| --- | --- |
| `GENERATING` | 占位行已落库，模型正在生成 |
| `COMPLETED` | 生成完成，content 为完整回答 |
| `FAILED` | 生成失败，保留已生成的部分内容 |
| `CANCELLED` | SSE 超时或客户端断开，保留部分内容 |

状态收尾一律 `WHERE status='GENERATING'` 原子更新，迟到的回调不会覆盖已收尾的行。

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
| `eventId` | 信封事件 ID，consumed_event 消费幂等键（裸格式消息兜底用 messageId） |
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

---

## 用户自定义 AI 服务配置接口

用户可以保存 OpenAI 兼容的 HTTPS API 地址、API Key 和模型列表。API Key 使用 AES-256-GCM 加密后写入数据库，接口只返回固定掩码。

## 环境变量

启动 `service-ai` 前必须配置 32 字节主密钥的 Base64 字符串：

```powershell
$env:API_KEY_MASTER_KEY="<base64-key>"
```

主密钥不能提交到代码仓库，丢失后已有 API Key 无法解密。

## 获取远程模型列表

```http
POST /api/ai/configs/models
Content-Type: application/json
```

```json
{
  "baseUrl": "https://api.example.com/v1",
  "apiKey": "sk-example"
}
```

服务端请求 `GET {baseUrl}/models`。出于 SSRF 防护，只允许无查询参数或片段的 HTTPS 公网地址；服务端会校验 DNS 返回的全部 IPv4/IPv6 地址，拒绝本机、私网、链路本地、多播及保留地址。模型列表和流式请求均禁止跟随重定向，并校验重定向目标后显式拒绝响应。

## 创建配置

```http
POST /api/ai/configs
Content-Type: application/json
```

```json
{
  "groupName": "个人模型",
  "modelNames": ["model-a", "model-b"],
  "aiUrl": "https://api.example.com/v1",
  "apiKey": "sk-example"
}
```

同一用户的 `groupName` 不能重复。

## 查询配置

```http
GET /api/ai/configs
GET /api/ai/configs/{configId}
```

用户只能查询自己的配置。

## 更新配置

```http
PUT /api/ai/configs/{configId}
```

请求字段与创建接口相同。`apiKey` 为空时保留原 API Key，传入新值时重新加密。

## 删除配置

```http
DELETE /api/ai/configs/{configId}
```

## 接入原问答接口

继续使用原 SSE 接口：

```http
POST /api/ai/advice/ask
```

自动选择平台模型：

```json
{
  "conversationId": 1,
  "question": "为什么这段代码越界？",
  "code": "..."
}
```

使用用户模型：

```json
{
  "conversationId": 1,
  "question": "为什么这段代码越界？",
  "code": "...",
  "userAiConfigId": 12,
  "modelName": "model-a"
}
```

`userAiConfigId` 和 `modelName` 必须同时为空或同时提供。服务端会校验配置归属以及模型是否属于该配置。

## 用户可见异常

- `400`：参数错误、API Key 无效、模型不属于配置。
- `403`：访问了其他用户的配置。
- `429`：远程 AI 服务限流或额度不足。
- `502`：远程 AI 服务异常。
- `500`：服务内部异常。

---

## 函数模式随机测试生成接口

## 创建生成任务

```http
POST /api/question/function/test-cases/generate
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "questionId": 1001,
  "language": "java",
  "standardAnswer": "class Solution { public int solve(int value) { return value * 2; } }",
  "count": 20,
  "seed": 123456
}
```

- 仅题目创建者或管理员可调用，禁用账号不可调用。
- `count` 默认为 `20`，范围为 `1-100`。
- `seed` 可不传；传入相同种子可以复现同一批随机输入。
- 当前支持 `int`、`long`、`String`、`int[]`、`String[]` 参数。
- 后端随机生成输入，用标准答案计算输出，全部成功后批量写入隐藏测试用例。
- 接口返回异步任务信息，初始状态为 `PENDING`。

## 查询生成状态

```http
GET /api/question/function/test-cases/generate/status?taskId=<taskId>
Authorization: Bearer <token>
```

任务状态包括 `PENDING`、`SUCCESS`、`FAILED`。任务仅创建者本人可查询，并在 Redis 中保留 30 分钟。

默认随机范围：

- `int`：`[-1000, 1000]`
- `long`：`[-100000, 100000]`
- `String`：长度 `0-20`，内容为大小写字母和数字
- `int[]`：长度 `0-20`，元素范围 `[-100, 100]`
- `String[]`：长度 `0-10`，单个字符串长度 `0-10`

随机生成只保证参数类型正确，不保证满足题目的跨参数业务约束。例如“两数之和一定存在答案”这类约束，后续需要增加题目级生成规则。
