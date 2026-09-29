# service-ai AI 服务说明与接口

## 1. 当前完成范围

当前 AI MVP 已形成三个闭环：

1. WA、RE、TLE 等判题失败后异步生成建议，先入库，再通过消息服务 WebSocket 推送。消费幂等与进度由 `consumed_event` 状态机（`PROCESSING/COMPLETED/FAILED`）在数据库落底，通知发送成功前事件绝不定为 COMPLETED。
2. 用户在题目侧边栏追问，后端通过 POST SSE 流式返回模型文本，完成后保存消息。
3. 每次回答落库后异步检查会话长度，由 Ollama 小模型增量压缩或重建会话记忆。

三个对话闭环之外，另有辅助链路：`/task` 编辑增量静默观察（见第 9 节）、`ai.testcase.queue` 题目用例生成（见第 3 节）与面向 service-question 的函数产物生成（见文末）。

会话、消息和摘要以 MySQL 为事实来源，消费幂等由数据库 `consumed_event` 落底。Redis 不缓存摘要：它承载 `/task` 静默观察会话（初始代码快照、编辑增量与建议列表，键 TTL 50 分钟）、题目描述缓存（1 分钟）以及 api-governance 的 Redis 滑动窗口限流；摘要互斥锁由 Redisson 提供。长期用户画像与跨题记忆暂未实现。

## 2. 模块职责

```text
service-ai/src/main/java/org/example/serviceai
├── controller
│   ├── AiAdviceController              会话查询、消息分页、SSE 追问与 AI 任务接口
│   ├── UserAiConfigController          用户自定义模型配置 CRUD 与模型列表
│   └── FunctionArtifactController      函数产物接收（内部接口，内部 Token 鉴权）
├── advice                               全局异常处理：GlobalExceptionHandler 继承 service-common 的 AbstractGlobalExceptionHandler，只声明 basePackages 并补充 AiProviderHttpException 分支；错误码见 exception/AiErrorCode（服务号 06）
├── conversation
│   ├── dto                              AskDto 与游标分页结果
│   ├── enums                            USER / ASSISTANT / SYSTEM
│   ├── repository                       会话存储接口及 MyBatis 实现
│   ├── service
│   │   ├── AdviceConversationService    问答编排与消息落库
│   │   ├── AdvicePromptBuilder          首次建议和追问 Prompt
│   │   └── AiConversationMemaryService  摘要更新、重建与锁
│   └── vo                               题目会话索引
├── entry                                Conversation / Message / MessageStatus / ConsumedEvent / ConsumedEventStatus / AiConversationMemory / UserAiConfig
├── mapper                               MyBatis-Plus Mapper
├── intifer                              CallAi 适配器接口与 LangChain4j Ollama 声明式接口
├── serviceQuestion                      TestCaseBuilder / TestCaseParser（题目用例 Prompt 与解析）
├── MQ
│   ├── Mq                              AI 双队列分发器（ai.advice.queue / ai.testcase.queue，延迟重试 / 死信分派）
│   ├── AiMessageHandler               消息处理器接口（按路由键注册，实现方不自行 ACK）
│   └── handler/AiDeadLetterHandler    死信登记（consumed_event 置 FAILED）
├── handle/testcasehandle
│   ├── WAAiHandle                     判题失败（WA/RE/TLE）建议消费者（事件状态机）
│   └── TestCaseHandle、GLMTestCaseHandle、QwenTestCaseHandle
│                                      题目测试用例生成消费者（ai.testcase.routing，成功后经 question.exchange 回传用例；终态失败补偿删除题目）
└── service                            AIService、ConsumedEventService、UserAiService、ApiKeyCryptoService、FunctionArtifactService、AiAdviceTaskService 与模型适配器（GLM/Qwen/GPT/Ollama）
```

Prompt 构建、模型调用、持久化和推送分别放在对应层，不由 Controller 或 Mapper 混合承担。

## 3. 自动建议链路

```text
service-judge 判题失败（Submit 流程判题为 WA/RE/TLE 时在事务内以 Outbox 登记
AI_ADVICE_REQUEST 事件，payload 只带 ID 引用；Retry 流程不触发）
  -> Outbox 事件（信封 eventId）→ ai.exchange / ai.wa-advice.routing
  -> ai.advice.queue（挂 ai.dlx，消费失败按 5s/10s/20s 延迟重试，3 次超限死信 ai.dead.queue）
  -> WAAiHandle：consumed_event 认领（eventId 幂等，裸格式兜底 messageId）
  -> 按 judgeRecordId 经 QuestionFeignClient#getJudgeContext 拉取判题上下文大字段
  -> 创建或查找用户/题目的根会话
  -> 云端模型生成建议
  -> 保存 ASSISTANT Message，并记 result_ref
  -> RabbitMQ AI_ADVICE 通知事件（发送成功才把事件置 COMPLETED）
  -> service-message / AiAdviceHandle
  -> WebSocket AI_ADVICE 用户队列
```

自动建议先入库再推送。通知发送失败时事件保持非 COMPLETED，由 ai.advice.wait.queue 延迟重试；重试命中 result_ref 时只补发通知、不重复生成。重试超限进入 `ai.dead.queue`，`AiDeadLetterHandler` 把事件置 FAILED 留存供人工重放（见 `docs/maintenance/messaging-reliability.md` 第 9 节）。WebSocket 允许丢失，但用户重新打开会话时仍能从数据库读取回答。AI 建议不进入通知中心收件箱。

同一分发器还消费 `ai.testcase.queue`（路由键 ai.testcase.routing，service-question 建题时经 Outbox/补跑任务投递）：TestCaseHandle 先查题目 `aiStatue`，已是 `success` 直接跳过；否则经 Feign 拉取题目、由 `TestCaseBuilder` 调用 `AIService` 生成测试用例，发回 `question.exchange` 的用例路由键入库；重试超限的终态失败会向 question 发「删除题目」补偿消息（testcase 裸格式消息无信封，死信时只留日志、不落 consumed_event）。

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

两处真实模型流式输出的修复必须保留：

1. **空响应守卫**：`ask()` 与 `askStream()` 收尾前检查全文 `isBlank()`——网关限流或流解析异常可能返回空串，空结果按 FAILED 收尾并上抛（交分发器重试或向客户端报 error），绝不落空 COMPLETED 行（否则前端出现空胶囊）。
2. **SSE 行解析**：Provider 解析按 `data:` 前缀匹配后再 `trim()`（SSE 规范允许冒号后不带空格，部分网关如 new-api 会省略——只认 `data: ` 会静默丢块导致回复截断）。该修复在 GLM/NikoApiGpt 适配器中；自定义用户模型通道（`UserAiService`）仍按 `data: ` 前缀解析。

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

每批最多取 14 条未压缩消息；无论增量还是重建，提交事件只保留批次内最近 4 条 `SYSTEM`，并与根题目、旧摘要和近期普通对话一起交给 Ollama。原始消息不删除；压缩失败或乐观锁更新失败时保留旧摘要，等待下一次异步任务继续尝试。

## 7. 模型职责

| 模型类型 | 当前职责 |
| --- | --- |
| 云端模型 | 自动判题建议、用户实时追问、题目用例生成与函数产物生成、`/task` 静默观察 |
| Ollama 小模型 | 会话摘要（增量压缩 / 重建），并作为优先级最低的兜底 Provider 参与轮转 |

已注册的平台 Provider 按优先级排序为：NikoApiGpt（`GPT`，0）、GLM（1）、QWEN（2）、Ollama（999）。GLM 与 NikoApiGpt 走 OpenAI 兼容 `chat/completions`，`endpoint` 默认官方公共网关、可经 Nacos 覆盖为自建网关；配置前缀分别为 `glm.*`、`nikoapigpt.*`、`qianwen.*`（各含 `apikey`/`model`，来自 Nacos `service-ai.yaml`）。QWEN 走 DashScope 原生非流式接口。Ollama 经 LangChain4j `OllamaChatModel`（`http://localhost:11434`，模型 `qwen2.5:14b`）。

不同模型统一实现 `CallAi`。`AIServiceManager` 按优先级选择健康 Provider，记录失败次数并进行切换；后台单线程定时执行真实探测（`isAvailable` 即发一条 "Reply with OK."，10 秒内返回非空视为健康），健康实例每三分钟探测一次，不健康实例每三十秒复检，关闭服务时同步停止探测线程。`AIService` 记录 Provider 名称、耗时、超时判断和根因。

## 8. 数据库与迁移

数据库名：`codewise_ai`。

完整建表：

```text
service-ai/src/main/resources/sql/codewise_ai.sql
```

主要约束：

```sql
UNIQUE KEY uk_ai_conversation_user_question (user_id, question_id);
UNIQUE KEY uk_ai_conversation_message (message_id);
KEY idx_ai_message_conversation_cursor (conversation_id, message_id);
UNIQUE KEY uk_ai_memory_conversation_user (conversation_id, user_id);
UNIQUE KEY uk_user_ai_config_group (user_id, group_name);
UNIQUE KEY uk_event_id (event_id);  -- consumed_event
```

表共五张：`ai_conversation`、`ai_message`、`ai_conversation_memory`、`user_ai_config`（`model_names` 为 JSON 列，`api_key` 存密文）、`consumed_event`。

服务端口 `8085`，自身无 context-path，控制器直接暴露 `/api/ai/**`；网关（8082）按 `Path=/api/ai/**` 路由到本服务，所有请求先经 `UserAuthInterceptor` 校验 `X-Internal-Token` 并填充 `UserContext`。本地 `application.yaml` 固定项：`spring.rabbitmq.listener.simple.acknowledge-mode: manual`（手动 ACK 必须显式声明，否则与分发器双重确认导致 channel 崩溃）、`outboxpro.enabled: false`（本服务只消费不生产）、`codewise.internal-token: ${CODEWISE_INTERNAL_TOKEN}`、`api.governance`（Redis 滑动窗口限流，超限返回 429「AI 请求过于频繁，请稍后重试」；管理端点 `/api/ai/governance` 仅管理员角色可访问）。数据源、`security.api-key-master-key` 与各 Provider 配置由 Nacos 数据源 `service-ai.yaml` 下发。

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
| `POST` | `/api/ai/advice/ask` | SSE 流式追问（POST，SSE 端点） |
| `GET` | `/api/ai/advice/{conversationId}/messages` | 按 `messageId` 游标分页 |
| `POST` | `/api/ai/advice/task` | 记录一条 AI 异步任务（编辑增量 + 静默观察判定） |
| `GET` | `/api/ai/advice/task?questionId=` | 查询当前用户在某题下的 AI 任务建议列表 |

以上接口均需登录（网关 JWT → `X-User-Id`，`UserAuthInterceptor` 校验 `X-Internal-Token`），并带 `@RateLimit` 滑动窗口限流（追问/任务写接口 20 次/分，查询 100 次/分）。`/task` 链路把初始代码与编辑增量存 Redis 观察会话：打开题目前 20 分钟只收集变化，此后每次 AI 静默判断间至少冷却 15 分钟、停笔 45 秒且 2 分钟内仍活跃才触发；AI 严格返回 `OK` 表示方向正确、不打扰用户，否则建议入列表并经 WebSocket 推送（`AITASKADVICE`）。

`GET /api/ai/configs`、`GET/PUT/DELETE /api/ai/configs/{configId}`、`POST /api/ai/configs`、`POST /api/ai/configs/models`（写接口 20-30 次/分限流，详见下文）与内部函数产物接收接口 `POST /api/ai/internal/function-artifacts`（除网关内部 Token 外，控制器另校验 `X-CodeWise-Internal-Token` 请求头，值必须等于 `CODEWISE_INTERNAL_TOKEN`，调用方为 service-question 的 `AiArtifactFeignClient`）。

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

用户可以保存 OpenAI 兼容的 HTTPS API 地址、API Key 和模型列表。API Key 使用 AES-256-GCM 加密后写入数据库（每次加密生成随机 12 字节 IV、128 bit 认证标签，落库格式为 `base64(IV).base64(密文)`）。列表接口只返回配置 ID、分组名与模型名；详情/创建/更新接口返回的 `apiKey` 为掩码（解密后保留前 3 后 3 字符，其余打星），明文永不出库。约束：分组名 ≤64 字符且同一用户唯一（数据库唯一键兜底）、模型最多 100 个、API Key ≤4096 字符；自定义模型请求固定 `temperature=0.7`、`max_tokens=1024`、`stream=true`，连接/读取超时 120 秒。

## 环境变量

启动 `service-ai` 前必须配置 32 字节主密钥的 Base64 字符串（注入到配置属性 `security.api-key-master-key`，仓库 yaml 不落盘，经环境变量或 Nacos 下发）：

```powershell
$env:API_KEY_MASTER_KEY="<base64-key>"
```

主密钥不能提交到代码仓库，丢失后已有 API Key 无法解密。本服务同时要求 `CODEWISE_INTERNAL_TOKEN`（无默认值，缺失启动失败）：既用于 `UserAuthInterceptor` 校验网关内部 Token，也用于函数产物接口校验 `X-CodeWise-Internal-Token`，必须与 service-question 配置同值。

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

失败统一由 `advice/GlobalExceptionHandler`（继承 `service-common` 的 `AbstractGlobalExceptionHandler`）转成 `Result`：**HTTP 仍为 200**，由 `code` 表达失败类型，文案一律取 `exception/AiErrorCode`（服务号 `06`）或 `CommonErrorCode`，模型/远程服务的原始报错只进服务端日志。完整编码规则见 [`../backend-controller-api.md`](../backend-controller-api.md) 的「错误码与业务异常」。

| 错误码 | 枚举 | 场景 |
| --- | --- | --- |
| `4000601`–`4000622` | `CONFIG_CONTENT_REQUIRED` 等 | 模型配置入参：分组名、API Key 长度、模型服务地址（须 https、非内网、不带查询参数）、模型选择等 |
| `4010601` | `INTERNAL_CALL_UNAUTHORIZED` | 服务间内部调用凭证无效 |
| `4030601` | `CONFIG_NOT_ACCESSIBLE` | 访问了其他用户的模型配置 |
| `4030602` | `PROVIDER_KEY_INVALID` | 远程平台返回 API Key 无效或权限不足（HTTP 401/403） |
| `4040601`/`4040602` | `CONVERSATION_NOT_FOUND` / `ADVICE_MESSAGE_NOT_FOUND` | 会话或建议消息不存在 |
| `4090601` | `GROUP_NAME_DUPLICATED` | 分组名称重复 |
| `4090602` | `SSE_STREAM_INTERRUPTED` | SSE 连接中断，本次回答停止生成 |
| `4290601` | `PROVIDER_QUOTA_EXCEEDED` | 远程平台限流或额度不足（HTTP 429） |
| `5000601` | `AI_SERVICE_NOT_READY` | 无可用供应商/未知内部异常 |
| `5000602`–`5000618` | `PROVIDER_CALL_FAILED` 等 | 远程模型不可响应、返回内容不可解析/不完整、密钥加解密失败、上下文与通知读写失败 |

注意：不再有 `502` 这类透传 HTTP 状态——远程服务故障一律映射为上面的 `50006xx` 业务码。

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

- 鉴权：`/api/question/function` 控制器带类级 `@RequireAdmin`（仅管理员/root 可访问，`roleId` 经 Feign 回查 service-user），服务端再校验「题目创建者或管理员」，且题目必须是 FUNCTION 类型。
- `count` 默认为 `20`，范围为 `1-100`。
- `seed` 可不传（缺省取 `System.nanoTime()`，不可复现）；传入相同种子可以复现同一批随机输入。随机输入会去重，去重后数量不足会报错要求换种子。
- `language` 缺省即 `java`；标准答案（`standardAnswer`）长度上限 100000 字符，当前只支持 Java。
- 当前支持 `int`、`long`、`String`、`int[]`、`String[]`、`TreeNode`（层序 JSON 数组，可含 null）参数。
- 后端随机生成输入后，把标准答案作为调试代码经判题链路（RabbitMQ debug 路由）异步执行，全部执行成功（AC/WA）才取实际输出批量写入题目测试用例；任一输入执行失败即任务 FAILED。任务与结果存 Redis，保留 30 分钟。
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
- `TreeNode`：约 10% 概率空树，否则 1-15 个节点、节点值 `[-100, 100]`，按层序数组（含 null 空洞）输出

随机生成只保证参数类型正确，不保证满足题目的跨参数业务约束。例如“两数之和一定存在答案”这类约束，后续需要增加题目级生成规则。

---

## 函数题 AI 产物生成（对拍）链路

随机生成之外的另一条函数题用例链路，由 service-question 的 LeetCode 产物导入（`POST /api/question/function/leetcode/artifacts/generate?titleSlug=`，管理员）驱动，service-ai 只负责生成源码：

```text
service-question LeetCodeArtifactImportService（异步任务）
  -> AiArtifactFeignClient：POST /api/ai/internal/function-artifacts
     请求头 X-CodeWise-Internal-Token = CODEWISE_INTERNAL_TOKEN（两侧服务必须同值），
     同时仍受 UserAuthInterceptor 的 X-Internal-Token 校验
  -> service-ai FunctionArtifactService：
     按 function-artifact-v1 契约让云端模型（AIService.callAi，max_tokens 8192）返回两份
     Java 17 源码 generatorCode（public class Generator，args[0]=seed、args[1]=count，
     逐行输出 JSON 数组随机输入）与 standardAnswerCode（public class Main，逐行输出期望值）；
     剥离 Markdown 围栏解析 JSON，并校验契约关键字（类名、随机数生成器、args 参数），不符即失败
  -> service-question 把源码落盘 codewise.function-artifact.root
     （默认 ./data/function-artifacts/<taskId>/，文件名 <slug>-generator.java /
     <slug>-standard-answer.java，不入库），编译执行由 InternalJavaArtifactJudge 完成
```

service-ai 侧不落库、不感知产物目录；模型只生成「生成器 + 标准答案」两段可执行源码，绝不直接枚举测试样例，样例数量与种子由 service-question 在运行时传给 Generator。
