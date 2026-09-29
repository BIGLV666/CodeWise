# CodeWise 刷题复习流程与项目功能说明

## 1. 项目概述

CodeWise 是一个面向编程学习和在线刷题的综合平台，核心目标是把传统 OJ 刷题、代码判题、错题沉淀、间隔复习、AI 辅助学习串成一个完整学习闭环。

项目采用多服务架构，主要包含：

| 模块 | 服务名 | 主要职责 |
| --- | --- | --- |
| 网关服务 | `service-gateway` | 统一入口、路由转发、JWT 鉴权、用户信息透传 |
| 用户服务 | `service-user` | 用户注册、登录、用户信息、头像上传 |
| 题目服务 | `service-question` | 题目管理、测试用例管理、提交记录、判题入口、判题结果回写、函数题解析与测试用例生成 |
| 判题服务 | `service-judge` | 消费判题任务、Docker 容器池执行代码、生成判题结果、失败判题登记与重试 |
| 复习服务 | `service-review` | 收藏夹、复习计划、每日复习快照、SM-2 复习调度、笔记、学习计划（进度追踪） |
| 社区服务 | `service-community` | 帖子、题解、评论、点赞、内容审核与下架、申诉、热榜 |
| AI 服务 | `service-ai` | 判题失败建议、SSE 追问、会话摘要、测试用例生成、函数判题产物、用户自定义模型配置 |
| Agent 服务 | `codewise-agent` | Python FastAPI 门面 + dsh Node 工具运行时：对话、SSE 流式、经网关回调各服务的 Agent 工具 |
| 消息服务 | `service-message` | 邮件、通知中心与 WebSocket 推送 |
| 公共模块 | `service-common` | MQ 配置、Redis 配置、用户上下文、公共 DTO、工具类 |
| API 模块 | `service-api` | Feign 接口、跨服务 DTO |

---

## 2. 核心业务闭环

CodeWise 的核心学习闭环可以概括为：

```text
用户刷题
  ↓
提交代码
  ↓
异步判题
  ↓
获得判题结果
  ↓
根据结果更新题目统计和提交记录
  ↓
如果来自复习模块，则更新复习状态
  ↓
按照 SM-2 算法安排下一次复习
```

该闭环把“刷题结果”转化为“复习计划”，让用户不仅能做题，还能根据历史掌握情况进行长期复习。

---

## 3. 普通刷题流程

普通刷题指用户从题库、题目详情页等入口提交代码，不参与复习计划推进。

### 3.1 提交流程

```text
前端
  ↓ POST /api/question/judge
service-question / JudgeController
  ↓
JudgeService 创建 submit_record（judge_status = pending；事务内同步写入 outboxpro_outbox 表，OutboxPro）
  ↓
Outbox Relay 异步投递：judge.exchange / judge.routing（统一事件信封，payload 为 submitRecordId）
  ↓
service-judge 消费判题任务（CAS 领取：pending -> judging）
  ↓
Docker 容器池执行代码并生成 judge_record
  ↓
判题结果与回调事件同事务写入 Outbox，Relay 投递：question.submit.record.routing
  ↓
service-question / SubmitRecordHandel 回写结果（judging -> success CAS 收尾）
  ↓
经 message.exchange / websocket.routing 交给 service-message，由其 WebSocket 推送判题结果给前端
  ↓
若 submitScene 为 REVIEW / PLAN，同事务登记复习/学习计划事件，由 Outbox 转发 service-review
```

### 3.2 提交请求示例

```json
{
  "questionId": 1001,
  "language": "java",
  "code": "public class Main { public static void main(String[] args) {} }",
  "submitScene": "NORMAL"
}
```

### 3.3 submitScene 说明

| 值 | 场景 | 说明 |
| --- | --- | --- |
| `NORMAL` | 普通刷题 | 只更新提交记录、题目统计和判题结果推送 |
| `REVIEW` | 复习刷题 | 除普通判题逻辑外，还会经 Outbox 通知复习服务推进 SM-2 复习计划 |
| `PLAN` | 学习计划刷题 | 判题结果绑定到 service-review 当天学习计划（ProgressTracker），见第 13 章 |

`submitScene` 为**必填**字段：缺失或不在枚举内时，`JudgeService.getType` 直接抛出业务异常
（「提交场景不能为空」/「不支持的提交类型」），后端不会再默认按 `NORMAL` 处理。

---

## 4. 判题服务流程

判题服务 `service-judge` 负责执行代码，不直接处理前端请求。

### 4.1 判题任务来源

题目服务在创建提交记录的同一数据库事务内，把 `submitRecordId` 写入 `outboxpro_outbox` 表（OutboxPro）；后台 Relay 批量投递到 RabbitMQ（Publisher Confirm 确认）：

```text
exchange: judge.exchange
routingKey: judge.routing
message: 统一事件信封（eventId/eventType/schemaVersion/occurredAt/producer/traceId），payload 为 submitRecordId
```

这样保证「提交记录落库成功 ⇒ 消息必然可投递」，不会出现业务提交成功但消息丢失的幽灵状态。

### 4.2 判题处理过程

```text
JudgeSubmitConsumer 消费 judge.submit.queue
  ↓（载荷信封/裸 Long 双读；业务失败按 5s/10s/20s 指数退避重试，超限进死信）
JudgeSubmitHandler -> JudgeTaskService.handleSubmit：
CAS 领取提交记录（pending -> judging，重复投递/被抢占直接短路）
  ↓
查询 submit_record 与题目类型（ACM 普通题 / FUNCTION 函数题）
  ↓
查询测试用例（ACM 查 test_case，函数题查 function_test_case 并用 Main 调用器包装用户代码）
  ↓
从容器池借出同语言容器，先编译（失败即 CE），再按测试点顺序执行
  ↓（遇到首个非 AC 测试点即短路返回，failIndex 记录失败点序号）
生成 JudgeRecord
  ↓
judge_record 落库 + Outbox 事件同事务提交：
  - JUDGE_RESULT_CALLBACK -> service-question（payload 为 judgeRecordId）
  - 结果为 WA/RE/TLE 时追加 AI_ADVICE_REQUEST -> service-ai
  ↓
归还容器（污染容器销毁重建）
```

### 4.3 判题结果状态

| 状态 | 含义 |
| --- | --- |
| `AC` | Accepted，全部通过 |
| `WA` | Wrong Answer，答案错误 |
| `TLE` | Time Limit Exceeded，超时（容器内 `timeout` 退出码 124） |
| `RE` | Runtime Error，运行错误（非 0 退出码且非超时） |
| `CE` | Compile Error，编译错误 |

判题器由 `BuildResult` 按退出码/输出比对归类，当前**不产生独立的 MLE 状态**：内存用量随结果
一并记录（`memoryUsed`），超限由 Docker 执行层限制（容器内存上限 256MB），不会映射为单独的判题状态。

### 4.4 判题并发与容器池

判题吞吐 = MQ 消费并发 × 容器池容量的联动设计（`JudgeSubmitConsumer`、`ContainerPoolManager`）：

- 消费并发由配置项 `codewise.judge.consumer-concurrency` 控制，**默认 2**；超过池容量只会在
  池等待队列空转，因此扩容需与容器池同步上调。
- 启动时按语言预创建容器池：**Java 2 个、Python/C++/C 各 1 个**（`INITIAL_CONTAINER_COUNTS`）。
  支持的语言即这四种（`LanguageSpec`），函数模式当前仅支持 Java。
- 借出等待上限 10 秒（`CONTAINER_BORROW_TIMEOUT_SECONDS`），超时借不到容器按失败进入重试链路。
- 执行超时或进程树清理失败的容器会被标记**污染**（taint），归还时销毁并重建同语言容器，防止串题；
  工作区清理失败同样触发重建。
- 单测试点时限 2000ms（`LanguageSpec.TIME_LIMIT_MS`）、编译超时 15s、批量执行启动宽限 7s；
  Docker 执行侧限制内存 256MB、CPU 单核配额、PID 上限 64、非 root 用户运行。
- 管理员通过 `/api/judge/containers` 查询各语言池状态（空闲/借出/等待/配置容量）并扩容、删除空闲容器，
  每种语言至少保留一个容器；操作要求管理员身份（roleId=2）。

### 4.5 判题失败与重试链路

正常判题的提交记录状态机为 `judge_status`：`pending -> judging -> success`；
判题消息重试超限进入死信后：

```text
JudgeSubmitConsumer 三次指数退避（judge.wait.queue TTL 弹回）仍失败
  ↓
nack 进 judge.dead.queue
  ↓
JudgeDeadLetterHandler：登记 failure_submit（PENDING）+ submit_record 置 failure（同事务）
  ↓
管理员经 /api/judge/failure/{status}/list 查看、/api/judge/failure/retry/{id} 重试
  → 投递 judge.retry.routing，JudgeRetryHandler 复用判题链路重跑
  ↓
兜底：FailureSubmitReconcileTask 每分钟修正停留超过 5 分钟的记录
（已有判题结果 -> SUCCESS；仍无结果 -> 回到 PENDING 待重试）
```

另有一层题目服务侧的补偿：`SubmitTask` 每 5 分钟扫描停留 `pending` 超过 5 分钟的提交记录，
重新投递到 `judge.exchange / judge.routing`，防止 Outbox 极端情况下消息未达。

---

## 5. 判题结果回写流程

判题完成后，`service-question` 会消费判题结果消息。

### 5.1 回写流程

```text
SubmitRecordHandel 接收 judgeRecordId（信封/裸 Long 双读）
  ↓
查询 judge_record、submit_record
  ↓
独立事务内收尾：
  更新 submit_record：submitStatus、timeUsed、memoryUsed（judging -> success 条件更新 CAS，重复投递只会命中一次）
  同事务更新 question 统计：totalSubmit、totalAc（仅 CAS 命中的那次消费才计数）
  submitScene = REVIEW / PLAN 时，同事务经 Outbox 登记复习/学习计划事件转发 service-review
  ↓
事务提交后才 basicAck（幂等跳过同样 ACK）；失败按 Redis 计数重试，3 次超限留存后丢弃
  ↓
ACK 之后尽力而为：发送 WebsocketSendDto 到 message.exchange / websocket.routing，
由 service-message 推送判题结果
```

### 5.2 WebSocket 推送

判题结果的 WebSocket 推送不在题目服务本地完成：`service-question` 把 `WebsocketSendDto`
发往 `message.exchange / websocket.routing`，由 `service-message` 的 `WebSocketPushService`
推送到用户专属队列：

```text
/queue/judge-result-{userId}
```

推送是 ACK 之后的尽力而为动作，失败只告警、不影响已提交的回写业务（数据库提交记录才是事实源）。
自定义调试（`POST /api/question/debug`）走同样的模式，结果推送 `/queue/debug-judge-result-{userId}`；
推送副本对 code/log/输出等大字段做 16KB 截断，数据库写入不受影响。

---

## 6. 复习模块整体设计

复习模块位于 `service-review`，主要负责：

```text
收藏夹
复习计划
每日复习快照
SM-2 间隔重复算法
判题结果驱动的复习状态更新
笔记（文件夹 + 多类型笔记）
学习计划 / 进度追踪（ProgressTracker，PLAN 提交场景绑定）
复习提醒与掌握祝贺通知
```

### 6.1 核心表模型

| 表 | 说明 |
| --- | --- |
| `review` | 用户对某道题的长期复习状态（status：0 学习中 / 1 已掌握 / 2 已暂停） |
| `review_record` | 用户某一天的复习任务快照 |
| `review_config` | 用户复习配置 |
| `favorites` | 收藏夹 |
| `note` / `note_folder` | 笔记与笔记文件夹（见第 13 章） |
| `progress_tracker` | 学习计划（按题目+日期一条，含 submit_ids，见第 13 章） |
| `consumed_event` | MQ 消费幂等台账（事件认领/完成状态） |

---

## 7. 复习计划生成流程

用户进入复习模块时，会请求今日复习计划。

### 7.1 接口

```http
GET /api/review/review/today
```

兼容旧路径：

```http
GET /api/review/review/gettodayreview
```

### 7.2 生成逻辑

```text
前端请求今日复习计划
  ↓
service-review 查询今天是否已有 ReviewRecord
  ↓
如果已有：直接读取今日快照
  ↓
如果没有：查询到期的 Review 记录
  ↓
生成今日 ReviewRecord 快照
  ↓
调用 service-question 批量查询题目信息
  ↓
返回今日复习题目列表
```

### 7.3 今日快照含义

`review_record` 是用户当天第一次进入复习模块时生成的复习计划快照。

它包含：

| 字段 | 说明 |
| --- | --- |
| `pendingReviewQuestionIds` | 今天待完成复习的题目 ID |
| `completedReviewQuestionIds` | 今天已经完成复习的题目 ID |
| `acQuestionIds` | 今天复习中 AC 的题目 ID |
| `reviewDate` | 复习日期 |
| `reviewDays` | 连续复习天数 |

快照生成后，当天不会因为新的题目状态变化随意追加不属于快照的题目，避免破坏“今日计划”的一致性。

---

## 8. 复习刷题流程

复习刷题指用户从复习模块进入题目并提交代码。

### 8.1 前端提交

```json
{
  "questionId": 1001,
  "language": "java",
  "code": "...",
  "submitScene": "REVIEW"
}
```

### 8.2 完整流程

```text
前端从复习模块提交代码（submitScene = REVIEW）
  ↓
service-question 创建 submit_record，submitScene = REVIEW
  ↓
Outbox 投递判题任务，service-judge 判题并生成 judge_record
  ↓
service-question 回写提交结果（CAS 收尾事务内）
  ↓
同事务经 Outbox 登记 REVIEW_JUDGE_RECORD 事件（payload 为 ReviewJudgeRecordDto）
  ↓
Relay 投递 reviews.exchange / reviews.judge.record.routing
  ↓
service-review 计算 quality
  ↓
service-review 更新 review 表中的 SM-2 状态
  ↓
service-review 更新当天 review_record 快照
```

---

## 9. 复习判题结果事件

当复习场景判题完成后，`service-question` 会向 `service-review` 发送复习判题结果事件。

### 9.1 MQ 信息

```text
exchange: reviews.exchange
routingKey: reviews.judge.record.routing
queue: reviews.queue
```

PLAN 场景（学习计划）使用同一 exchange、独立路由与独立队列，避免同队列双监听形成竞争消费：

```text
routingKey: plan.judge.record.routing
queue: reviews.plan.queue
```

两者消息结构一致，载荷均为 `ReviewJudgeRecordDto`（PLAN 需要 userId/questionId 定位当天计划）。

### 9.2 消息体 ReviewJudgeRecordDto

```json
{
  "userId": 1,
  "questionId": 1001,
  "submitRecordId": 10,
  "judgeRecordId": 20,
  "acTestTotal": 8,
  "allTestTotal": 10,
  "status": "WA",
  "errorMessage": null
}
```

### 9.3 service-review 消费逻辑

```text
校验 routingKey
  ↓
解析 ReviewJudgeRecordDto（信封/裸 DTO 双读）
  ↓
读取用户复习配置
  ↓
如果关闭自动复习：ACK 后跳过
  ↓
根据判题结果计算 quality
  ↓
如果本次不计入复习：ACK 后跳过
  ↓
Redisson 用户级当日锁（review:record:{userId}:{date}）串行化快照读改写
  ↓
事务内：consumed_event 按 review:judge:{judgeRecordId} 认领幂等行（已完成则 ACK 跳过）
  ↓
更新 Review（SM-2 状态）
  ↓
首次由「学习中」转「已掌握」时，同事务经 Outbox 登记 REVIEW_MASTERED 祝贺事件
  ↓
更新今日 ReviewRecord（pending -> completed，AC 入 acQuestionIds）
  ↓
同事务幂等行置 COMPLETED，随后 ACK；失败按 Redis 计数重投，3 次超限留存丢弃
```

---

## 10. SM-2 复习算法

复习模块使用 SM-2 思路，根据用户本次提交表现动态调整下一次复习时间。

### 10.1 quality 映射规则

| quality | 场景 |
| --- | --- |
| `5` | AC，全部通过 |
| `4` | 未 AC，但测试点通过率 ≥ 80% |
| `3` | 测试点通过率 ≥ 60% |
| `2` | 测试点通过率 ≥ 30% |
| `1` | 测试点通过率 > 0 |
| `0` | 完全未通过，或 CE 且配置为计入复习 |
| `-1` | 本次提交不计入复习，例如 CE 且配置为不计入，或系统内部错误 |

### 10.2 更新规则

```text
quality < 3
  ↓
复习失败
repetitions = 0
intervalDays = 1
nextReviewTime = now + 1 day

quality >= 3
  ↓
复习有效
repetitions + 1
第 1 次正确：intervalDays = 1
第 2 次正确：intervalDays = 6
第 3 次及以后：intervalDays = oldIntervalDays * easinessFactor
```

当新的 `intervalDays` 达到 `masteredIntervalDays` 时，`review.status` 由 0（学习中）置为 1（已掌握），
并触发 `REVIEW_MASTERED` 祝贺事件（经 Outbox 通知消息服务）。

此外用户可手动维护复习条目：`updateReview` 支持调整下次复习时间、复习权重（weight，用于到期查询排序）
和状态（0 恢复学习中 / 2 已暂停，到期查询只取 status=0）；已进入今日快照的题目不允许修改，
已掌握的题目不能再转为暂停。

---

## 11. 复习配置

### 11.1 查询配置

```http
GET /api/review/review/config
```

如果当前用户没有配置，系统会自动创建默认配置（`reviewCount` 默认不限量、
`enableAutoReview = 1`、`countCompileError = 1`、`minEasinessFactor = 1.30`、
`initialEasinessFactor = 2.50`、`masteredIntervalDays = 30`）。

### 11.2 更新配置

```http
PUT /api/review/review/config
Content-Type: application/json
```

请求示例：

```json
{
  "reviewCount": 20,
  "enableAutoReview": 1,
  "countCompileError": 1,
  "minEasinessFactor": 1.30,
  "initialEasinessFactor": 2.50,
  "masteredIntervalDays": 30
}
```

### 11.3 配置字段说明

| 字段 | 说明 |
| --- | --- |
| `reviewCount` | 每天最多推荐复习的题目数量 |
| `enableAutoReview` | 是否启用自动复习推进，`1` 开启，`0` 关闭 |
| `countCompileError` | 编译错误是否计入复习 |
| `minEasinessFactor` | 最低难度因子 |
| `initialEasinessFactor` | 初始难度因子 |
| `masteredIntervalDays` | 达到多少间隔天数后认为已掌握 |

---

## 12. 收藏夹功能

收藏夹模块用于用户整理题目。

### 12.1 主要能力

```text
创建收藏夹
查询收藏夹列表
查询收藏夹内题目
添加题目到收藏夹
从收藏夹移除题目
更新收藏夹信息
删除收藏夹
```

### 12.2 收藏夹与复习的关系

收藏夹偏向“题目整理”，复习模块偏向“长期学习计划”。

二者可以互相配合：

```text
用户收藏题目
  ↓
后续可以将题目加入复习计划
  ↓
系统根据判题表现安排下一次复习
```

---

## 13. 笔记与学习计划

`service-review` 在复习主链路之外承载两块能力：笔记和学习计划（进度追踪），两者同时提供用户端
接口（`/api/review/note`、`/api/review/progress`）和 Agent 工具端接口（`/api/review/agent/note`、
`/api/review/agent/...`），供 codewise-agent 经网关代用户操作。

### 13.1 笔记模块

```text
笔记文件夹（note_folder）+ 笔记（note）两级结构
  ↓
文件夹：创建/重命名/删除，列表返回各文件夹笔记数
笔记：游标分页列表（可按文件夹过滤）、详情、新建、更新、移动、删除
  ↓
folderId 为空表示「未分类」
```

笔记支持多类型内容：内容形态由 `NoteContentHandlerRegistry` 按类型分派处理器（当前有 Markdown
处理器），`contentType` 创建后不可变，正文经对应处理器规范化后落库。

### 13.2 学习计划（ProgressTracker）

用户按「题目 + 日期」创建学习计划，每天为每道题排一条计划。状态机为**单向不可逆**：

```text
0 未开始 → 1 进行中 → 2 已完成
                    ↘ 3 已过期（开始时间已过仍未完成，读取时懒判定刷新，不做定时任务）
```

- 仅「未开始」可修改开始时间；已过期/已完成的计划锁定开始时间，但备注与反思总结随时可改；
  已过期要重新安排只能删除重建。
- 支持单日/自然周/自然月日历聚合（题数与完成数）和周报表。

### 13.3 PLAN 提交如何驱动计划

```text
前端以 submitScene = PLAN 提交代码
  ↓
判题收尾事务内，service-question 经 Outbox 登记 PLAN_JUDGE_RECORD
  ↓
投递 reviews.exchange / plan.judge.record.routing -> reviews.plan.queue
  ↓
ProgressTrackerService.setPlan 消费
  ↓
用户级当日分布式锁（review:plan:{userId}:{date}）内：
  把 submitRecordId 追加进当天计划的 submit_ids（去重，重复投递幂等）
  判题结果为 AC 时计划置为已完成（status=2）
  无当天计划则直接跳过
```

消费失败按 Redis 计数重投（上限 3 次，超限留存失败消息），与复习消费的重试策略对齐。

---

## 14. 社区内容流程：发布、审核、下架与申诉

社区模块位于 `service-community`，承载帖子、题解、评论三类内容，围绕它们有完整的
「发布 → 审核 → 下架 → 申诉 → 恢复」流程和计数/热榜机制。

### 14.1 内容状态与发布

三类内容共用同一状态语义（以 `PostStatus` 定义）：

| 值 | 状态 | 说明 |
| --- | --- | --- |
| `0` | PENDING | 待审核：新发布内容的初始状态，不进信息流和热榜，仅作者本人「我的内容」可见 |
| `1` | NORMAL | 审核通过，正常可见 |
| `2` | TAKE_DOWN | 已下架（管理员强制下架，可申诉） |
| `3` | REJECTED | 审核拒绝（可申诉） |

发帖、题解等发布入口带 requestId 幂等（Redis setIfAbsent，2~3 分钟窗口）防重复提交；
信息流游标分页只返回 `NORMAL` 状态内容。

### 14.2 管理员审核与下架

`PostCheckService` 提供审核台能力：审核帖子/题解/评论时按动作放行（PASS→NORMAL）、
拒绝（REJECT→REJECTED）或下架（TAKE_DOWN），下架与恢复同样走该入口。
每次下架都会写入 `take_down_post` 留痕记录（内容类型、根 ID、关联题目 ID、下架原因、操作管理员）。
`take_down_post.take_down_id` 是雪花 ID，VO 序列化为字符串避免 JS 精度丢失。

审核结果通知经 Outbox 以固定 messageId（内容类型 + ID + 动作）投递消息服务，消费端幂等去重。

### 14.3 申诉闭环

```text
内容处于 TAKE_DOWN(2) 或 REJECTED(3)
  ↓
作者本人提交申诉（携带申诉理由；同一内容重复申诉时覆盖旧申诉，待审核中不允许重复提交）
  ↓
appeal.status = 0 待处理，管理员后台列表查看
  ↓
管理员处理：
  通过 -> 内容状态恢复 NORMAL，清理热榜/缓存中的下架成员，appeal.status = 2 已恢复
  拒绝 -> appeal.status = 1 已拒绝，记录管理员理由，内容维持下架/拒绝
  ↓
两种结果都经 Outbox 发送 APPEAL 通知（messageId = 申诉ID + 结果）给作者
```

### 14.4 点赞、浏览量与热榜

- 点赞写 Redis 增量桶（帖子/评论/题解三套），`LikeTask` 每分钟切换桶并把各桶增量回写数据库
  点赞数；题解浏览量同理由 `SolutionLookTask` 每分钟回写。多实例经 Redisson 锁竞争切换权，
  消费失败重新入队，保证不丢增量。
- 热榜由 Redis ZSet 维护（成员为帖子 ID，分数由点赞/评论数加权），`HostPostService` 提供
  Top10 读取；`HotPostTask` 每 5 分钟清理缓存对象触发按数据库数据重建，审核通过的帖子才入榜。
- 点赞通知由社区服务获取内容作者后经通知事件投递消息服务，固定 messageId 保证同一用户对同一
  内容只提醒一次，自赞不通知（见第 21 章）。

---

## 15. Agent 对话与工具调用闭环

`codewise-agent` 是独立于 `service-ai` 的 Python Agent 服务：FastAPI 门面 +
`agent-runtime/` 下的 dsh（DeepSeek Harness）Node 运行时，负责「通用对话 + 工具调用」，
而 `service-ai` 负责判题失败建议与题内 SSE 追问（见第 16 章）。

### 15.1 端到端流程

```text
前端携带用户 Bearer token 请求门面（Docker 部署经 nginx /agentapi/ 暴露）
  ↓
/api/agent/stream：SSE 流式对话；/api/agent/call：一次性调用
  ↓
门面用共享 JWT_SECRET 自校验 token 签名，从 sub 解析 userId（不信任模型生成的用户身份）
  ↓
每个会话懒启动一个 dsh 运行时子进程（JSON-RPC stdio 驱动），
会话 JSONL 日志落盘，进程重启按 session id 恢复，上下文压缩由 dsh 提供
  ↓
运行时加载 codewise-tools 插件（约 51 个工具，覆盖题目/提交/判题、收藏/复习/笔记/学习计划、
社区帖子/题解/评论/点赞等内容域）
  ↓
工具回调只指向部署环境配置的 CODEWISE_GATEWAY_URL，每次调用前从门面刷新的会话 token 文件
读取原始 Bearer token，经 service-gateway 正常鉴权进入各服务的 Agent*Controller
```

### 15.2 身份与数据边界

- Agent 工具走的是与前端相同的网关链路：网关剥离伪造头、注入 `X-User-Id` 等身份头，下游
  `UserContext` 生效——因此 Agent 代用户执行写操作（发帖、点赞、建计划等）与用户本人操作同权同校验。
- 会话与消息持久化在 `codewise_ai` 库的 `agent_*` 表；用户自定义模型配置（provider、API Key
  密文，`API_KEY_MASTER_KEY` AES-GCM 加密）同样由门面管理。
- `codewise-web` 插件对工具内的网页抓取做 SSRF 防护（URL 形态校验 + IP 黑名单 + DNS 解析比对，
  fail-closed）。

---

## 16. AI 功能与演进

AI 服务当前独立为 `service-ai`，已形成判题建议、题目会话和会话记忆闭环。

### 16.1 当前能力

```text
- WA、RE、TLE 判题结果由 service-judge 经 Outbox 发布 AI_ADVICE_REQUEST 事件触发异步生成建议；
  建议落库后发 notification.exchange / ai.advice 路由，由 service-message 写通知中心并经
  WebSocket 推送给前端。
- 用户在题目会话中通过 SSE 流式追问（/api/ai/advice/ask），复用同一会话上下文。
- 根题目、会话摘要、最近消息和当前代码共同构建上下文。
- Ollama 小模型异步压缩会话摘要（只提炼事实，不回答用户），云端模型负责主要回答。
- 多模型健康检查、优先级和调用失败降级；支持用户自定义模型配置（API Key 加密存储）。
- AI 测试用例生成（两条链路，见 16.5）与函数题解析导入（支持力扣拉题）。
- 函数判题产物生成接口（内部令牌鉴权，供题目服务调用）。
```

### 16.2 与刷题复习闭环的关系

未来 AI 模块可以结合：

```text
用户提交记录
题目通过率
复习质量评分
错误类型
题目标签
掌握度变化
```

为用户生成更个性化的学习建议。

### 16.3 AI 服务网关与熔断切换

`service-ai` 内部通过 `AIServiceManager` 对多个模型服务进行统一管理，可以理解为 AI 调用层的轻量级服务网关。

它不直接把业务代码绑定到某一个模型，而是把不同模型实现统一抽象为 `CallAi` 接口，再由管理器负责选择当前可用服务。

核心机制包括：

| 机制 | 说明 |
| --- | --- |
| 多模型注册 | 启动时收集所有 `CallAi` 实现，例如 Qwen、GLM 等模型服务 |
| 优先级排序 | 按模型服务的 `priority` 排序，优先使用更高优先级的模型 |
| 健康状态缓存 | 使用 `healthCache` 记录每个模型当前是否可用 |
| 失败计数 | 使用 `failCount` 统计模型连续调用失败次数 |
| 熔断标记 | 当某个模型连续失败达到阈值后，将其标记为不可用 |
| 自动切换 | 当前模型失败后，自动切换到下一个可用模型重试 |
| 定时健康检查 | 后台线程每 30 秒检查一次模型可用性，并恢复健康模型状态 |

### 16.4 AI 调用失败处理流程

AI 调用入口统一经过 `AIService`：

```text
业务服务发起 AI 调用
  ↓
AIService 请求 AIServiceManager 获取可用模型
  ↓
调用当前模型服务
  ↓
调用成功：记录成功，清空失败计数
  ↓
调用失败：记录失败次数
  ↓
失败次数达到阈值：标记该模型不可用
  ↓
切换到下一个模型并重试
  ↓
所有模型不可用：返回 AI 服务调用失败
```

该设计可以避免业务层直接依赖单个模型服务。当某个模型接口超时、限流、密钥异常或服务不可用时，系统可以自动尝试切换到其他模型，提升 AI 功能的可用性。

当前实现属于轻量级熔断和故障切换，后续可以继续扩展为：

- 增加失败窗口时间，避免永久累计失败次数。
- 支持半开状态，让熔断模型在一段时间后小流量试探恢复。
- 为不同 AI 场景配置不同模型优先级，例如测试用例生成优先稳定模型，题解生成优先效果模型。
- 将模型健康状态暴露为管理接口，方便前端或后台查看当前 AI 服务状态。
- 接入 Resilience4j 等成熟熔断组件，统一处理超时、重试、限流和熔断策略。

### 16.5 测试用例生成的两条链路

**LLM 直出链路（ACM 题目）**：题目服务把题目信息经 `ai.exchange / ai.testcase.routing` 发给
`service-ai`（Qwen / GLM 处理器），模型生成用例后经 `question.exchange / question.testcace.routing`
回流，题目服务 `TestCaseHandle` 按「输入+期望输出」MD5 去重落库并把题目 `ai_statue` 收尾为
`success`。`QuestionAiStatueTask` 每 5 分钟兜底扫描：`pending` 超时或 `failure` 的题目重置为
`pending` 并重发；测试用例数已达 5 个及以上直接标记 `success`。

**函数题随机生成链路（以标准答案驱动）**：题目作者提交函数题标准答案后，
`FunctionTestCaseGenerationService` 按函数参数配置随机生成输入（默认 20 组、上限 100 组，可指定
seed），把「标准答案 + 随机输入」作为调试任务经判题服务的调试队列在 Docker 中执行得到输出，
结果回填生成任务（任务态存 Redis，30 分钟 TTL）并转换为函数测试用例。函数题解析服务还支持从
力扣拉取题目（`LeetCodeArtifactImportService` / `FunctionQuestionParseService`），配套的题目侧
`InternalJavaArtifactJudge` 可在本地用判题镜像校验生成产物。

---

## 17. 项目功能总览

### 17.1 用户模块

- 邮箱注册与验证码验证
- 用户登录
- JWT 鉴权
- 用户信息管理
- 头像上传

### 17.2 题目模块

- 新增题目（ACM / 函数两种题型）
- 查询题目详情
- 游标分页查询题目
- 更新题目
- 删除题目
- HTML/OJ 题目导入、力扣函数题导入
- AI 题目状态维护（`ai_statue`：pending / success / failure，定时兜底重试）
- 函数题解析、函数测试用例随机生成与判题产物校验

### 17.3 测试用例模块

- 新增测试点
- 查询测试点
- 更新测试点
- 删除测试点
- 统计题目测试点数量

### 17.4 判题模块

- 提交代码
- 异步判题（Outbox 事务消息 + CAS 领取）
- Docker 沙箱执行 + 判题容器池（预创建、借还、污染重建、管理员扩缩容）
- 判题失败登记、死信重试与管理员重试、定时补偿
- 判题结果回写
- 自定义调试（含函数测试用例生成复用调试链路）
- WebSocket 实时推送结果（经消息服务）

### 17.5 复习模块

- 添加题目到复习计划
- 获取今日复习计划
- 生成每日复习快照
- 消费复习判题结果（consumed_event 幂等台账 + 用户级当日分布式锁）
- 根据 SM-2 更新下次复习时间
- 维护 pending/completed/ac 复习进度
- 配置每日复习数量和算法参数
- 复习状态手动维护（权重、暂停/恢复、调整下次复习时间）
- 首次掌握触发祝贺通知

### 17.6 收藏夹模块

- 创建收藏夹
- 查询收藏夹
- 添加题目（含批量，供 Agent）
- 删除题目
- 更新收藏夹
- 移动题目到其他收藏夹
- 删除收藏夹

### 17.7 笔记与学习计划模块

- 笔记文件夹与笔记的增删改查、移动、游标分页
- Markdown 等多类型笔记内容处理
- 学习计划创建/批量创建、更新、删除
- 计划状态懒刷新（未开始/进行中/已完成/已过期）
- 单日/周/月日历聚合与周报表
- PLAN 提交场景判题结果自动绑定当天计划（AC 即完成）

### 17.8 社区模块

- 发帖/编辑/删除、题解、评论（含楼中楼）与标签
- 信息流与题目维度题解的游标分页
- 点赞（帖子/评论/题解，Redis 增量桶异步回写）
- 内容审核台（放行/拒绝/下架/恢复）与下架留痕
- 用户对被下架/被拒绝内容的申诉与管理端处理
- 热榜（Redis ZSet，定时清缓存重建 Top10）
- 「我的内容」聚合视图

### 17.9 AI 模块

- AI 测试用例生成（LLM 直出 + 标准答案随机生成两条链路）
- 判题失败 AI 建议（WA/RE/TLE 事件驱动）与 SSE 流式追问
- 会话记忆压缩（Ollama 摘要）
- 多模型统一适配、平台模型 + 用户自定义模型配置（API Key 加密）
- AI 服务健康检查、失败计数与熔断标记、多模型自动故障切换

### 17.10 Agent 模块（codewise-agent）

- 会话创建/重命名/删除、历史消息查询
- SSE 流式对话，透传 dsh 会话事件
- 约 51 个经网关回调的项目工具（题目/提交/复习/收藏/笔记/计划/社区内容域）
- SSRF 防护的受控网页抓取

### 17.11 消息与通知模块

- 邮件验证码与系统邮件。
- 点赞通知、复习提醒、审核结果、申诉结果、AI 建议、复习掌握祝贺等站内通知消费入库。
- 站内通知倒序游标分页、未读数、详情自动已读和软删除。
- WebSocket 握手鉴权与用户队列实时推送。
- Redis 短期幂等、数据库 `message_id` 唯一索引兜底，以及 consumed_event 消费幂等台账。

---

## 18. 整体流程图

```text
用户登录
  ↓
浏览题目 / 进入复习计划 / 安排学习计划
  ↓
提交代码（submitScene = NORMAL / REVIEW / PLAN）
  ↓
service-question 创建 SubmitRecord（事务内 Outbox 登记判题事件）
  ↓
RabbitMQ 投递判题任务
  ↓
service-judge 领取任务、容器池执行代码
  ↓
生成 JudgeRecord（判题结果 + AI 建议事件同事务 Outbox 登记）
  ↓
RabbitMQ 回传判题结果
  ↓
service-question CAS 收尾提交记录、更新题目统计
  ↓
经 message.exchange 交由 service-message WebSocket 推送判题结果
  ↓
若 submitScene = REVIEW：service-review 更新 Review / ReviewRecord（SM-2 推进、掌握祝贺通知）
若 submitScene = PLAN：service-review 把判题结果绑定当天学习计划（AC 即完成）
  ↓
失败结果（WA/RE/TLE）：service-ai 生成建议，经通知中心入库 + WebSocket 推送
  ↓
生成下一次复习计划
```

---

## 19. 当前设计特点

### 19.1 优点

- 判题服务独立，便于隔离代码执行风险；判题走预创建 Docker 容器池，隔离资源且避免冷启动。
- 使用 MQ 解耦提交和判题，适合异步任务处理。
- 关键链路事务性 Outbox（OutboxPro）：提交、判题结果、复习/计划转发、各类通知均与业务写库
  同事务登记，Relay 至少一次投递，消除「库已提交、消息丢失」。
- 消费侧幂等体系：CAS 状态收尾、consumed_event 台账、messageId 唯一键、Redis 重试计数 +
  死信/留存兜底，重复投递自然跳过。
- WebSocket 推送提升刷题反馈体验。
- 复习模块引入 SM-2 算法，形成长期学习闭环。
- AI 服务独立，方便后续扩展模型能力。
- AI 调用层支持多模型管理、健康检查和失败自动切换，降低单模型不可用风险。
- Agent 服务复用网关鉴权链路代用户操作，工具不信任模型生成身份。
- 网关统一入口，剥离可伪造身份头并注入可信身份，便于鉴权和路由管理。

### 19.2 后续优化方向

- 拆分 `ReviewService` 内部职责，例如 `ReviewPlanService`、`ReviewQualityEvaluator`、`Sm2Calculator`。
- 增加复习模块单元测试，优先测试 SM-2 算法和 quality 映射。
- 完善 AI 熔断策略，例如失败时间窗口、半开恢复、超时控制和限流保护。
- 统一命名规范，例如 `Handler`、`Feign`、`util`。
- 后续根据规模考虑独立通知服务 `service-notification`。
---

## 20. 2026-07-08 维护记录

### 20.1 题目列表游标分页

- 主页题目查询接口使用 `/api/question/cursorquestions`。
- 分页方式从传统页码改为游标分页：第一页不传 `lastId`，后续请求传上一页返回的 `nextCursor`。
- 接口返回 `CursorPageResult<ReturnQuestionDto>`，使用 `hasNext` 判断是否继续加载。
- `ReturnQuestionDto.status` 是当前用户维度的题目状态，不是题目上下架状态：
  - `0`：未尝试。
  - `1`：已通过，来源于该用户该题任意一次 `submit_status = 'AC'`。
  - `2`：提交过但未通过。

### 20.2 submit_record 冗余字段与索引

- `submit_record` 增加 `question_title varchar(50)`，用于提交历史、复习提交、消息推送等场景直接展示题目标题。
- `submit_record` 增加联合索引 `idx_user_id_question_id__status(user_id, question_id, submit_status)`，用于题目列表批量聚合当前用户提交状态。
- 普通提交和复习提交都应在创建 `SubmitRecord` 时写入 `questionTitle`，避免后续 MQ 消息或历史记录展示时再次查题目表。

### 20.3 用户主页资料接口

- 用户模块补充昵称、个人简介、生日修改接口。
- 头像上传增强文件校验，只允许真实图片及常见图片扩展名。
- 头像更新失败时会清理本次新上传文件；更新成功后会尝试清理旧头像文件。

### 20.4 复习提交流程

- `ReviewJudgeRecordDto` 携带 `questionTitle`，复习模块收到判题结果后可以直接用于推送和复习记录展示。
- 复习提交继续通过 `submitScene = REVIEW` 区分，普通提交保持 `NORMAL`。

## 21. 2026-07-15 通知与复习提醒维护记录

### 21.1 点赞通知

帖子、评论和题解新增点赞后，由 `service-community` 获取内容作者并异步发布通知事件。固定消息 ID 保证同一用户对同一内容只提醒一次，自赞不产生通知。

### 21.2 复习提醒

`service-review` 的 `ReviewMessageTask` 在每天 10:00（`NotRecordMessageTask`）提醒未创建今日计划的用户，
21:00（`haveRecordMessageTask`）提醒已创建但仍有待完成题目的用户。多实例通过 Redisson 日期锁竞争执行权；
提醒不再裸直发 MQ，而是逐用户小事务经 Outbox（`REVIEW_REMINDER`）投递，messageId 按天幂等。

### 21.3 通知中心

`service-message` 消费点赞和复习消息，先写入 `codewise_message.notification_center`，再尝试 WebSocket 推送。列表只返回通知摘要，详情返回正文与 JSON 扩展数据并自动标记已读。

### 21.4 Long 精度

跨服务 `UserDto.userId` 和通知中心直接返回前端的 ID 使用字符串表示，避免雪花 ID 超过 JavaScript `Number.MAX_SAFE_INTEGER` 后发生精度丢失。

## 22. 2026-09 流程校验更新

本次对全文流程与代码做了核对与补充，要点：

- 判题链路：补充消费并发（`codewise.judge.consumer-concurrency`，默认 2）、容器池预创建/借还/污染重建、
  指数退避重试 + 死信 + `failure_submit` 管理员重试 + 每分钟补偿任务；判题状态表移除 MLE（判题器不产生该状态）。
- `submitScene` 改为必填并新增 `PLAN` 场景；复习/计划事件、判题结果回调、AI 建议事件统一走事务性 Outbox（OutboxPro）。
- 复习消费补充 `consumed_event` 幂等台账与用户级当日分布式锁，新增首次掌握的祝贺事件。
- 新增章节：笔记与学习计划（13）、社区内容流程与申诉（14）、Agent 对话与工具调用闭环（15）、
  测试用例生成两条链路（16.5）；功能总览与流程图同步更新。
