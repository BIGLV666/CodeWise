# CodeWise 架构全景 · 业务链路图 · 面试亮点

> 2026-08 整理。覆盖 8 个 Spring Boot 服务 + 2 个共享模块 + Python Agent 的总体架构、
> 全部核心业务链路、消息可靠性/安全架构，以及按主题组织的面试讲述要点。
> 实现细节见 `docs/maintenance/messaging-reliability.md`、`docs/technical-design.md`。

---

## 1. 总体架构

```
┌────────────────────────────── 客户端 ───────────────────────────────┐
│   Web 前端（HTTP / SSE / WebSocket）        codewise-agent（Python）  │
│                                            FastAPI 门面 + dsh Node  │
│                                            工具运行时，复用用户原始   │
│                                            Bearer Token 回调网关     │
└───────────────┬──────────────────────────────────┬──────────────────┘
                │                                  │
                ▼                                  ▼
┌──────────────────────── service-gateway :8082 ──────────────────────┐
│ ① JWT 校验（白名单精确匹配）                                          │
│ ② 先剥离客户端伪造头 X-User-Id/Name/X-Internal-Token/X-Real-IP        │
│ ③ 再注入可信值（内部 Token 来自 CODEWISE_INTERNAL_TOKEN 环境变量）     │
│ ④ IP 信任策略：默认只信 TCP remoteAddress，LB 部署才开 trust-forwarded-for │
└──┬─────────┬──────────┬───────────┬───────────┬──────────┬─────────┘
   ▼         ▼          ▼           ▼           ▼          ▼
 user:8081 question:8084 message:8083 ai:8085 community:8087 review:8097
 (账户/    (题目/提交/  (WebSocket   (AI 建议/    (帖子/评论/  (SM-2 复习
  邮箱)     函数题)      推送/邮件/    SSE 追问/     点赞/申诉)   计划/提醒)
  │          │          通知中心)     会话记忆)      │           │
  │          │ 经 RabbitMQ 驱动        │             │           │
  │          ▼                        │             │           │
  │      judge:8086（不经网关）         │             │           │
  │      Docker 沙箱判题·容器池预热     │             │           │
  ▼          ▼          ▼            ▼             ▼           ▼
codewise_user codewise_question(判题服务共库) codewise_message codewise_ai codewise_community codewise_review
```

**基础设施**：MySQL 8（每服务一库，judge 复用 codewise_question）、Redis（限流/幂等/
分布式锁/热榜）、RabbitMQ（7 组业务交换机 + judge.dlx/ai.dlx 死信拓扑）、Nacos（注册+配置，连接信息不出仓库）、
Docker（`codewise-java-judge:17` 判题镜像，容器池 2 Java + 1 Python/C/C++）。

**共享模块**：`service-api`（Feign 契约 + DTO + `Result<T>` + 事件信封/EventTypes）、
`service-common`（JWT、UserContext、MQ 拓扑声明、Outbox 组件、信封编解码、统一 Feign
身份透传拦截器）。**身份传播链**：网关注入头 → `UserAuthInterceptor` 校验内部 Token →
`UserContext`(ThreadLocal) → Feign 拦截器向下游再透传，客户端永远无法伪造身份。

---

## 2. 核心业务链路图

### 2.1 ACM 判题主链路（提交 → 判题 → 结果）

```
用户提交代码
  │
  ▼ gateway JWT 校验 + 头清洗
  ▼
question:8084 ── 事务①：submit_record 落库(pending)
  │               + OutboxPro.publish(JUDGE_SUBMIT_REQUEST)   ← 同事务，杜绝"库里有没有消息"的撕裂
  │
  ▼ OutboxPro Relay（每秒批量 · FOR UPDATE SKIP LOCKED · 多实例安全 · Publisher Confirm · 指数退避 · DEAD 台账可告警）
  │
  ▼ RabbitMQ judge.submit.queue（挂 judge.dlx）
  │
judge:8086 ─ JudgeSubmitConsumer（毒消息死信 / 失败 5s/10s/20s 等待队列延迟重试 / 超限死信）
  │   JudgeSubmitHandler：
  │   ① CAS 领取：UPDATE submit_record SET judge_status='judging' WHERE judge_status='pending'
  │   ② Docker 容器执行（事务外——避免长事务持数据库锁跑分钟级任务）
  │   ③ 事务②：judge_record 落库 + Outbox.append(JUDGE_RESULT_CALLBACK，WA/RE/TLE 再加 AI_ADVICE_REQUEST)
  │
  ▼ RabbitMQ question.queue
  │
question SubmitRecordHandel（非事务消费者 + TransactionTemplate + 提交后 ACK）
  │   事务③：CAS judging→success（门控）→ total_submit(+AC) 计数 → REVIEW 场景 append(REVIEW_JUDGE_RECORD)
  │   事务提交 → basicAck → WebSocket 推送（尽力而为，DB 是事实源）
  │
  ▼ message:8083 WebSocket 推给前端（断线可查库补齐）
```

**一句话**：三个事务 + 两段 Outbox + 两次 CAS，把"提交-判题-回写-计数-转发"做成
不丢消息、不重复计数、ACK 永远在事务提交之后的闭环。

### 2.2 判题失败 → AI 建议 → 推送

```
judge 事务② 同时 append AI_ADVICE_REQUEST（payload 只放 ID 引用，代码/日志等大字段
  由 AI 侧经 Feign 内部端点按需拉取——大字段不进 MQ）
  │
  ▼ ai.advice.queue（挂 ai.dlx）
  │
service-ai WAAiHandle —— consumed_event 事件状态机
  claim(eventId) → 生成建议落库 → markResultRef(建议消息ID) → 发通知
  ├─ 通知成功 → complete → COMPLETED → ACK
  ├─ 通知失败 → 异常上抛 → 分发器 5s/10s/20s 延迟重试
  │              → 重投命中 result_ref ⇒ 跳过生成、只补发通知（不重复调用 LLM）
  └─ 重试超限 → ai.dead.queue → AiDeadLetterHandler 置 FAILED 留存（人工可重放）
  │
  ▼ notification.queue
  │
message AiAdviceHandle（consumed_event 幂等：COMPLETED 唯一跳过态）→ WebSocket AI_ADVICE 队列
```

**一句话**：通知没发出去之前，事件绝不算完成；重试恢复用 result_ref 断点续传，
既不丢消息也不重复烧 LLM。

### 2.3 题目侧追问（POST SSE 流式）

```
POST /api/ai/advice/ask  (Accept: text/event-stream)
  │
  ▼ 插入 ai_message 占位行 status=GENERATING（content 先空串）
  ▼ streamAi 逐 chunk 回调 → SseEmitter chunk 事件
  ▼ 收尾（全部带 WHERE status='GENERATING' 原子守卫，防迟到回调覆盖）：
      正常完成 → COMPLETED（回填完整内容）
      异常     → FAILED（保留已生成部分内容）
      超时/断开→ CANCELLED（onTimeout 只取消本流占位行，不误伤同会话并发流）
  ▼ 错误事件只透出分类文案（认证失败/限流/HTTP 码），兜底固定文案——
    Provider 名、内部根因、URL 一律不出服务端日志
```

**一句话**：AI 回复从"要么成功要么消失"变成有状态、可恢复、可审计的四态生命周期。

### 2.4 函数题测试用例生成（AI 产物确定性验收）

```
创建者发起生成（owner/admin 校验）
  ▼ ai.testcase.queue → 测试用例 Handler（生成用例，重复 Provider 注册已知问题）
  ▼ question.queue → TestCaseHandle 去重入库（ACK 在事务提交后）
  ▼ 验收：InternalJavaArtifactJudge —— AI 生成的 Generator/Main 一律进 Docker 沙箱
      （network none / 256m / 1 CPU / 128 pids / 只读根 / noexec tmpfs），
      宿主机执行路径已删除，Docker 不可用直接失败不降级；恰好 50 组数据强校验
```

**一句话**：AI 只负责"写"，对错由确定性编译执行说了算，且永远在沙箱里说。

### 2.5 复习计划（SM-2 + 每日提醒）

```
REVIEW 场景判题结果 → Outbox(REVIEW_JUDGE_RECORD) → reviews.queue
  ▼ ReviewService.setReview：
      Redisson 日级锁 → 事务{ consumed_event.claim(review:judge:{judgeRecordId})
        → 今日快照成员判断 → SM-2（答错：repetitions=0、间隔重置 1 天、EF 降；
           答对：间隔按 EF 放大；达 masteredIntervalDays → Mastered 终态）
        → complete }  ← claim 行与业务同事务：回滚即消失，重投可重新接管
  ▼ 每日 10:00/21:00 定时扫描（Redisson 锁防多实例重复）
      → Outbox(REVIEW_REMINDER，messageId 按 天+用户 幂等) → notification.queue
      → ReviewHandle（信封/裸双读 + Redis/DB 唯一键双层幂等）→ 通知中心 + WebSocket
  ▼ 掌握祝贺：SM-2 间隔达到阈值 状态 0→1 首次转掌握时
      → 同事务 Outbox(REVIEW_MASTERED：userId/questionId/掌握时间/加入时间/总复习次数)
      → notification.review.mastered.routing → ReviewMasteredHandle（信封双读 + consumed_event 幂等）
      → Feign 异步取题目名 → 收件箱(REVIEW_MASTERED) + WebSocket INBOX_REVIEW 祝贺推送
      （掌握后 status=1 不再进入每日快照 = 自动移出复习计划）
```

**一句话**：经典 SM-2 间隔重复算法 + 事件级幂等 + Outbox 提醒，整条链路可重放。

### 2.6 社区互动 / 通知中心 / 邮件

```
点赞/评论/判题通过/申诉 → notification.exchange → notification.queue
  → 4 个通知 Handler：业务成功后才写幂等标记 + notification_center 唯一键兜底
     （失败 Redis 计数重试 3 次 → 失败留存）→ 通知中心收件箱 + WebSocket 实时推
邮件：注册/验证码 → message.queue → EmailService（consumed_event 幂等；
  发送失败重试 3 次 → FAILED 留存可人工重放；日志只记收件人/主题不落验证码）
```

### 2.7 身份与内部调用链（横切）

```
客户端 ──(可能带伪造 X-User-Id 等)──▶ gateway：剥离 → 注入可信值
   gateway ──▶ 下游服务：UserAuthInterceptor 校验 X-Internal-Token(环境变量,无默认值)
              → UserContext(ThreadLocal) → afterCompletion 清理
   服务A ──Feign──▶ 服务B：拦截器从 UserContext 重新透传身份三件套 + 内部 Token
   WebSocket 握手：同样校验内部 Token 后才信任 X-User-Id
   Python Agent：从 JWT sub 取身份，绝不信任模型生成的 userId，原 Token 经网关回调
```

---

## 3. 消息可靠性架构（三层防线 + 全拓扑）

```
生产侧                     传输侧                        消费侧
┌──────────────┐   ┌──────────────────────┐   ┌────────────────────────┐
│ ①Transactional│   │ ②统一事件信封          │   │ ③consumed_event 状态机   │
│   Outbox      │──▶│  eventId/eventType/   │──▶│  claim(PROCESSING) →    │
│ 业务与事件同事务 │   │  schemaVersion/       │   │  业务成功 complete       │
│ FOR UPDATE    │   │  traceId/payload      │   │  (COMPLETED)；COMPLETED │
│ SKIP LOCKED   │   │ EnvelopeCodec 双读    │   │  唯一跳过态；崩溃残留可   │
│ 退避+DEAD重放  │   │ (灰度期新旧格式共存)    │   │  重新接管(at-least-once) │
└──────────────┘   └──────────────────────┘   └────────────────────────┘
                        + DLX 延迟重试拓扑：
   失败 → wait 队列(per-message TTL 5s/10s/20s) → DLX 弹回主队列；3 次超限 → DLQ 死信留存
```

```
MQ 全拓扑（新队列一律挂 DLX；旧队列参数不可变 ⇒ 换名迁移+排空，是 RabbitMQ 生产经验点）
judge.exchange  → judge.submit/debug/retry.queue(挂 judge.dlx) + judge.wait.queue(TTL 弹回)
ai.exchange     → ai.testcase/advice.queue(挂 ai.dlx) + 两个 wait 队列 + ai.dead.queue
question.exchange → question.queue（回调/用例/删除）
notification.exchange → notification.queue（like/review/ai-advice/checked/appeal 5 路由）
message.exchange → message.queue（email/websocket）
reviews.exchange → reviews.queue（判题结果→复习）
```

覆盖消费者：question `SubmitRecordHandel`/`TestCaseHandle`（CAS 幂等）、ai `WAAiHandle`、
message `EmailService`/`AiAdviceHandle`、review `ReviewService`（consumed_event）；
judge 侧消费者统一"提交后 ACK + 头计数退避重试 + 毒消息死信"。

---

## 4. 安全架构

| 层 | 措施 |
|---|---|
| 网关 | JWT 校验；**先剥离后注入**四个身份头；公共路径白名单精确匹配（拒绝 contains("login") 类宽匹配）；IP 默认只信 remoteAddress |
| 服务间 | 内部 Token 三处同源（网关注入/拦截器校验/Feign 透传），环境变量注入**无默认值，缺失启动即失败**；WebSocket 握手同校验 |
| 判题沙箱 | Docker：network none、--memory 256m、--cpus 1.0、--pids-limit 64、只读根文件系统、/workspace 与 /tmp 受限 tmpfs、非 root、cap-drop ALL、no-new-privileges、进程树清理防 fork 炸弹；容器池预热消除冷启动；AI 产物验收沙箱更强（pids 128、/tmp noexec），宿主机执行路径已物理删除 |
| 密钥 | 仓库零密钥（datasource/redis/rabbitmq/mail 全在 Nacos；internal-token/JWT secret 走环境变量）；自定义模型 API Key AES-256-GCM 加密存储（主密钥 env 注入） |
| AI 出口 | 自定义模型 URL：仅 HTTPS 公网、校验 DNS 全部解析 IP（防 rebinding）、拒绝私网/环回、禁止跟随重定向；模型列表/单 chunk/总回答限长 |
| 应用层 | Redis 滑动窗口限流（api-governance starter，网关+服务两级）；SSE 错误脱敏；日志不落 Token/验证码/密钥 |
| 研发流程 | Mimosa 安全扫描门禁拦 commit（存量高危强制修复才能提交）；约 150 个离线单元测试 |

---

## 5. 面试亮点（按主题，每个都给"问题→方案→讲法"）

### A. 消息可靠性（最大亮点，建议开场）

**A1. Transactional Outbox 消除双写**
- 问题：DB 提交了但 MQ 没发出去（或反之），判题任务凭空丢失或幽灵消息污染数据。
- 方案：业务事务内 publish 事件行（OutboxPro 库 `outboxpro_outbox`，judge/question/review/community
  各自生产者库同构），Relay 每秒 `FOR UPDATE SKIP LOCKED` 批量认领投递——多实例并发安全，
  **Publisher Confirm 确认后才标 SENT**；失败指数退避，5 次转 DEAD 并入台账，
  高水位自动告警（`OUTBOXPRO_ALERT`），运维端点可查。
- 讲法："我没有引入 Seata/Kafka 事务，只用一张表+一个定时器把 XA 问题降维成了
  本地事务+至少一次投递+消费幂等，这在面试里是标准的 Outbox 模式落地。"

**A2. 统一事件信封 + 双读灰度迁移**
- eventId（全局幂等键）/eventType/schemaVersion/occurredAt/producer/traceId/payload；
  `EnvelopeCodec` 判定式双读，新旧格式共存灰度，生产消费两侧可独立发布。
- 讲法："接口演进不停服——schemaVersion + 双读是我做协议兼容的标准手法。"

**A3. consumed_event 消费状态机（数据库幂等替代 Redis 先写标记）**
- 问题：原实现"先 setIfAbsent 标记再执行业务"，业务失败后标记残留，重投被当重复
  消费 ACK 掉——消息双重丢失；且 Redis 标记与 DB 事务无关联，存在"标记已写、事务回滚"。
- 方案：三状态 PROCESSING/COMPLETED/FAILED + 唯一 event_id；**COMPLETED 是唯一跳过态**，
  PROCESSING/FAILED 在重投时重新接管（at-least-once）；claim 行与业务更新**同事务**，
  回滚即消失。已在 message/ai/review 三个服务落地同一骨架。
- 讲法：能画出状态机图并解释"为什么 COMPLETED 之前任何状态都可重入"。

**A4. ACK 纪律与 DLX 延迟重试**
- 消费者一律"非事务方法 + TransactionTemplate 事务体 + **提交后 ACK**"；
  失败按 `x-codewise-retry-count` 头指数退避（5s/10s/20s），消息原样转投无消费者
  wait 队列，TTL 到期经 DLX 弹回主队列——**RabbitMQ 无延迟插件实现延迟重试**；
  3 次超限死信，DLQ 消费者登记失败并置 FAILED 留存，支持人工重放。
- 踩坑经验：RabbitMQ 队列参数不可变，给老队列补 DLX 必须换新队列名+排空迁移——
  这个生产细节面试官很认。

**A5. CAS 幂等计数**
- 判题结果回写：`UPDATE ... SET judge_status='success' WHERE judge_status='judging'`
  作为门，命中才 `total_submit/total_ac+1` 并转发复习事件，三者同事务——
  重复投递天然幂等。讲法：对比"先查后判"的读-判-写竞态，为什么必须 CAS。

**A6. 断点续传式重试（AI 建议链路）**
- 建议生成后先记 `result_ref` 再发通知；通知失败重试命中 result_ref 就跳过 LLM
  只补发通知。"通知没发出去之前事件绝不算完成"——把"提前标记完成"这类 bug
  的根因和修法讲清楚，是很好的故事。

### B. 判题系统

- **异步事件驱动架构**：提交即返回，判题经 MQ 异步，WebSocket 推送 + DB 事实源
  （断线后查库补齐——"WebSocket 只是通知手段，不是存储"）。
- **Docker 沙箱全家桶**（参数能背：network none / 256m / 1 cpu / 64 pids / 只读根 /
  受限 tmpfs / 非 root / cap-drop ALL / 进程树清理防 fork bomb；AI 产物验收沙箱更严：128 pids + /tmp noexec）。
- **容器池预热**：2 Java + 1 Python/C/C++ 常驻，消除冷启动；执行移出数据库事务，
  避免长事务占连接。
- **执行纪律**：AI 生成的判题产物强制沙箱验收（"AI 只写、确定性执行说了算"）；
  实验性 Go 判题器因沙箱未就绪直接桩化而不是带洞上线。

### C. AI 工程化

- **SSE 流式 + 消息生命周期状态机**：GENERATING/COMPLETED/FAILED/CANCELLED 四态，
  占位行先落库、收尾全部 `WHERE status='GENERATING'` 原子守卫——迟到回调不覆盖、
  超时只取消本流（AtomicReference 捕获消息 ID 精准取消）。
- **双 AI 路径**：判题失败建议（MQ 异步）+ 用户追问（SSE 同步流式）；会话记忆用
  Ollama 小模型**增量压缩**（<14 条跳过 / <3000 字增量 / 超限重建保留最近 SYSTEM 消息，
  乐观锁防并发覆盖）。
- **Provider 治理**：真实健康探测 + 失败计数熔断切换；首个 Provider 未出 chunk 才降级，
  防止两个模型的回答拼进同一条消息。
- **密钥与出口安全**：API Key AES-256-GCM 加密落库；自定义模型 URL 全套 SSRF 防护
  （HTTPS-only、DNS 全 IP 校验防 rebinding、禁私网、禁重定向）。
- **错误脱敏**：SSE 只透出分类文案，Provider 名/内部异常/URL 不出服务端。

### D. 微服务与数据

- **每服务一库**、跨服务只走 Feign（契约收在 service-api）或 MQ 事件（信封统一），
  杜绝跨库 join。
- **身份传播链**（网关剥离注入 → ThreadLocal → Feign 再透传 → WS 同校验），
  "客户端永远无法伪造身份"一句话讲完。
- **游标分页**（messageId 游标、倒序查询正序返回）+ 批量查询防 N+1。
- **Redis 实战**：Lua 原子限流切桶、热榜临时 Key 构建后原子切换、Redisson 分布式锁
  （复习日锁/定时任务防重/会话摘要锁）。
- **配置治理**：连接信息全在 Nacos、密钥全在环境变量、仓库零敏感值、缺失启动即失败。

### E. 工程素养（差异化加分）

- 约 150 个**纯离线**单元测试（不依赖中间件，Mockito 直测消费者/事务模板/Channel），
  重构有安全网；为 MyBatis-Plus 纯单测解决了表元信息初始化的坑。
- 文档体系：messaging-reliability.md 是"迭代手册"（新事件五步法、新队列纪律、
  排障顺序、三条人工重放通道、升级路径）。
- AI 协作开发流程：AGENTS.md 约定 + 并行子 agent 分工 + 安全扫描门禁拦 commit
  （存量高危必须修掉才能提交——本轮就是被它逼着把三处存量命令执行风险全修了）。

---

## 6. 高频追问速答

| 追问 | 答法要点 |
|---|---|
| 消息怎么保证不丢？ | 生产：Outbox 与业务同事务；传输：持久化队列+DLQ；消费：手动 ACK 在事务提交后；兜底：DEAD/FAILED 都有留存和重放通道 |
| 重复消费怎么办？ | 三层：信封 eventId → consumed_event 状态机（COMPLETED 唯一跳过态）→ 业务级 CAS/唯一键；接受 at-least-once，把幂等做进消费端 |
| 为什么不用 Redis 做消费幂等？ | Redis 标记与 DB 事务无原子性（先写标记后回滚→重投被跳过=丢消息）；claim 行进业务事务，回滚即消失。Redis 仍用于限流/锁/热榜等无需与 DB 原子的场景 |
| ACK 时机？ | 永远在事务提交之后；重试消息经 wait 队列 TTL 延迟弹回，避免立即 requeue 的热循环 |
| 为什么要换队列名？ | RabbitMQ 队列参数（DLX/TTL）不可变，改参数=新队列+旧队列排空迁移，管理台 moveTo |
| Docker 判题怎么防逃逸/资源耗尽？ | 无网络+全资源限制+只读根+受限tmpfs+非root+cap-drop ALL+进程树清理；容器池限并发天然背压 |
| AI 生成内容怎么保证正确？ | AI 只生成，验收用确定性编译+执行（沙箱内），50 组数据强校验；失败留状态不删题目 |
| SSE 断线/超时？ | 占位行四态机；断开判 IOException→CANCELLED 保留部分内容；DB 是事实源，刷新可恢复 |
| 事务里能发 MQ 吗？ | 直发不行（回滚=幽灵消息）；Outbox 同事务落表、Relay 异步投递，消费端幂等兜底 |
| 怎么灰度消息格式变更？ | 信封 schemaVersion + 双读 unwrap，新旧共存，全量后删裸格式分支 |
