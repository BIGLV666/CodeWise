# CodeWise 刷题复习流程与项目功能说明

## 1. 项目概述

CodeWise 是一个面向编程学习和在线刷题的综合平台，核心目标是把传统 OJ 刷题、代码判题、错题沉淀、间隔复习、AI 辅助学习串成一个完整学习闭环。

项目采用多服务架构，主要包含：

| 模块 | 服务名 | 主要职责 |
| --- | --- | --- |
| 网关服务 | `service-gateway` | 统一入口、路由转发、JWT 鉴权、用户信息透传 |
| 用户服务 | `service-user` | 用户注册、登录、用户信息、头像上传 |
| 题目服务 | `service-question` | 题目管理、测试用例管理、提交记录、判题入口、判题结果回写、WebSocket 推送 |
| 判题服务 | `service-judge` | 消费判题任务、执行代码、生成判题结果 |
| 复习服务 | `service-review` | 收藏夹、复习计划、每日复习快照、SM-2 复习调度 |
| AI 服务 | `service-ai` | 判题失败建议、SSE 追问、会话摘要和测试用例生成 |
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
JudgeService 创建 submit_record
  ↓
发送 MQ：judge.routing
  ↓
service-judge 消费判题任务
  ↓
执行代码并生成 judge_record
  ↓
发送 MQ：question.submit.record.routing
  ↓
service-question / SubmitRecordHandel 回写结果
  ↓
WebSocket 推送判题结果给前端
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
| `REVIEW` | 复习刷题 | 除普通判题逻辑外，还会通知复习服务更新复习计划 |

如果前端不传 `submitScene`，后端默认按 `NORMAL` 处理。

---

## 4. 判题服务流程

判题服务 `service-judge` 负责执行代码，不直接处理前端请求。

### 4.1 判题任务来源

题目服务创建提交记录后，会向 RabbitMQ 发送提交记录 ID：

```text
exchange: judge.exchange
routingKey: judge.routing
message: submitRecordId
```

### 4.2 判题处理过程

```text
JudgeServiceHandel 消费 submitRecordId
  ↓
查询 submit_record
  ↓
查询该题所有 test_case
  ↓
逐个测试点执行用户代码
  ↓
生成 JudgeRecord
  ↓
写入 judge_record 表
  ↓
发送 judgeRecordId 给 service-question
```

### 4.3 判题结果状态

| 状态 | 含义 |
| --- | --- |
| `AC` | Accepted，全部通过 |
| `WA` | Wrong Answer，答案错误 |
| `TLE` | Time Limit Exceeded，超时 |
| `MLE` | Memory Limit Exceeded，内存超限 |
| `RE` | Runtime Error，运行错误 |
| `CE` | Compile Error，编译错误 |

---

## 5. 判题结果回写流程

判题完成后，`service-question` 会消费判题结果消息。

### 5.1 回写流程

```text
SubmitRecordHandel 接收 judgeRecordId
  ↓
查询 judge_record
  ↓
查询 submit_record
  ↓
更新 submit_record：submitStatus、judgeStatus、timeUsed、memoryUsed
  ↓
更新 question 统计：totalSubmit、totalAc
  ↓
WebSocket 推送 judge-result
  ↓
如果 submitScene = REVIEW，发送复习判题结果事件到 service-review
```

### 5.2 WebSocket 推送

题目服务通过 WebSocket 推送判题结果：

```text
/queue/judge-result-{userId}
```

前端可以订阅该地址获取异步判题结果。

---

## 6. 复习模块整体设计

复习模块位于 `service-review`，主要负责：

```text
收藏夹
复习计划
每日复习快照
SM-2 间隔重复算法
判题结果驱动的复习状态更新
后续学习报表、AI 复习推荐扩展
```

### 6.1 核心表模型

| 表 | 说明 |
| --- | --- |
| `review` | 用户对某道题的长期复习状态 |
| `review_record` | 用户某一天的复习任务快照 |
| `review_config` | 用户复习配置 |
| `favorites` | 收藏夹 |

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
前端从复习模块提交代码
  ↓
service-question 创建 submit_record，submitScene = REVIEW
  ↓
发送 MQ 给 service-judge 判题
  ↓
service-judge 执行代码并生成 judge_record
  ↓
service-question 回写提交结果
  ↓
service-question 发送 ReviewJudgeRecordDto 到 service-review
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
解析 ReviewJudgeRecordDto
  ↓
读取用户复习配置
  ↓
如果关闭自动复习：ACK 后跳过
  ↓
根据判题结果计算 quality
  ↓
如果本次不计入复习：ACK 后跳过
  ↓
更新 Review
  ↓
更新今日 ReviewRecord
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

当新的 `intervalDays` 达到 `masteredIntervalDays` 时，可以将题目标记为已掌握。

---

## 11. 复习配置

### 11.1 查询配置

```http
GET /api/review/review/config
```

如果当前用户没有配置，系统会自动创建默认配置。

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

## 13. AI 功能与演进

AI 服务当前独立为 `service-ai`，已形成判题建议、题目会话和会话记忆闭环。

### 13.1 当前能力

```text
- WA、RE、TLE 等失败结果异步生成建议并通过 WebSocket 推送。
- 用户在题目会话中通过 SSE 流式追问。
- 根题目、会话摘要、最近消息和当前代码共同构建上下文。
- Ollama 小模型异步压缩会话摘要，云端模型负责主要回答。
- 多模型健康检查、优先级和调用失败降级。
- AI 生成测试用例与题目内容处理。
学习报表分析
```

### 13.2 与刷题复习闭环的关系

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

### 13.3 AI 服务网关与熔断切换

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

### 13.4 AI 调用失败处理流程

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

---

## 14. 项目功能总览

### 14.1 用户模块

- 邮箱注册与验证码验证
- 用户登录
- JWT 鉴权
- 用户信息管理
- 头像上传

### 14.2 题目模块

- 新增题目
- 查询题目详情
- 游标分页查询题目
- 更新题目
- 删除题目
- HTML/OJ 题目导入
- AI 题目状态维护

### 14.3 测试用例模块

- 新增测试点
- 查询测试点
- 更新测试点
- 删除测试点
- 统计题目测试点数量

### 14.4 判题模块

- 提交代码
- 异步判题
- Docker 沙箱执行
- 判题结果回写
- 自定义调试
- WebSocket 实时推送结果

### 14.5 复习模块

- 添加题目到复习计划
- 获取今日复习计划
- 生成每日复习快照
- 消费复习判题结果
- 根据 SM-2 更新下次复习时间
- 维护 pending/completed/ac 复习进度
- 配置每日复习数量和算法参数

### 14.6 收藏夹模块

- 创建收藏夹
- 查询收藏夹
- 添加题目
- 删除题目
- 更新收藏夹
- 删除收藏夹

### 14.7 AI 模块

- AI 测试用例生成
- 多模型统一适配
- AI 服务健康检查
- AI 调用失败计数与熔断标记
- 多模型自动故障切换
- 后续题解、学习建议、复习推荐扩展

### 14.8 消息与通知模块

- 邮件验证码与系统邮件。
- 点赞通知和每日复习提醒消费。
- 站内通知倒序游标分页、未读数、详情自动已读和软删除。
- WebSocket 握手鉴权与用户队列实时推送。
- Redis 短期幂等与数据库 `message_id` 唯一索引兜底。

---

## 15. 整体流程图

```text
用户登录
  ↓
浏览题目 / 进入复习计划
  ↓
提交代码
  ↓
service-question 创建 SubmitRecord
  ↓
RabbitMQ 投递判题任务
  ↓
service-judge 执行代码
  ↓
生成 JudgeRecord
  ↓
RabbitMQ 回传判题结果
  ↓
service-question 更新提交记录和题目统计
  ↓
WebSocket 推送判题结果
  ↓
如果 submitScene = REVIEW
  ↓
service-review 更新 Review / ReviewRecord
  ↓
生成下一次复习计划
```

---

## 16. 当前设计特点

### 16.1 优点

- 判题服务独立，便于隔离代码执行风险。
- 使用 MQ 解耦提交和判题，适合异步任务处理。
- WebSocket 推送提升刷题反馈体验。
- 复习模块引入 SM-2 算法，形成长期学习闭环。
- AI 服务独立，方便后续扩展模型能力。
- AI 调用层支持多模型管理、健康检查和失败自动切换，降低单模型不可用风险。
- 网关统一入口，便于鉴权和路由管理。

### 16.2 后续优化方向

- 将 MQ 消息 DTO 进一步事件化，例如 `JudgeCompletedEvent`、`ReviewProgressUpdatedEvent`。
- 拆分 `ReviewService` 内部职责，例如 `ReviewPlanService`、`ReviewQualityEvaluator`、`Sm2Calculator`。
- 增加复习模块单元测试，优先测试 SM-2 算法和 quality 映射。
- 完善 MQ 幂等、死信队列、失败重试策略。
- 完善 AI 熔断策略，例如失败时间窗口、半开恢复、超时控制和限流保护。
- 统一命名规范，例如 `Handler`、`Feign`、`util`。
- 后续根据规模考虑独立通知服务 `service-notification`。
---

## 17. 2026-07-08 维护记录

### 17.1 题目列表游标分页

- 主页题目查询接口使用 `/api/question/cursorquestions`。
- 分页方式从传统页码改为游标分页：第一页不传 `lastId`，后续请求传上一页返回的 `nextCursor`。
- 接口返回 `CursorPageResult<ReturnQuestionDto>`，使用 `hasNext` 判断是否继续加载。
- `ReturnQuestionDto.status` 是当前用户维度的题目状态，不是题目上下架状态：
  - `0`：未尝试。
  - `1`：已通过，来源于该用户该题任意一次 `submit_status = 'AC'`。
  - `2`：提交过但未通过。

### 17.2 submit_record 冗余字段与索引

- `submit_record` 增加 `question_title varchar(50)`，用于提交历史、复习提交、消息推送等场景直接展示题目标题。
- `submit_record` 增加联合索引 `idx_user_id_question_id__status(user_id, question_id, submit_status)`，用于题目列表批量聚合当前用户提交状态。
- 普通提交和复习提交都应在创建 `SubmitRecord` 时写入 `questionTitle`，避免后续 MQ 消息或历史记录展示时再次查题目表。

### 17.3 用户主页资料接口

- 用户模块补充昵称、个人简介、生日修改接口。
- 头像上传增强文件校验，只允许真实图片及常见图片扩展名。
- 头像更新失败时会清理本次新上传文件；更新成功后会尝试清理旧头像文件。

### 17.4 复习提交流程

- `ReviewJudgeRecordDto` 携带 `questionTitle`，复习模块收到判题结果后可以直接用于推送和复习记录展示。
- 复习提交继续通过 `submitScene = REVIEW` 区分，普通提交保持 `NORMAL`。

## 18. 2026-07-15 通知与复习提醒维护记录

### 18.1 点赞通知

帖子、评论和题解新增点赞后，由 `service-community` 获取内容作者并异步发布通知事件。固定消息 ID 保证同一用户对同一内容只提醒一次，自赞不产生通知。

### 18.2 复习提醒

`service-review` 在每天 10:00 查询未创建今日计划的用户，在 21:00 查询已创建但仍有待完成题目的用户。多实例通过 Redisson 日期锁竞争执行权。

### 18.3 通知中心

`service-message` 消费点赞和复习消息，先写入 `codewise_message.notification_center`，再尝试 WebSocket 推送。列表只返回通知摘要，详情返回正文与 JSON 扩展数据并自动标记已读。

### 18.4 Long 精度

跨服务 `UserDto.userId` 和通知中心直接返回前端的 ID 使用字符串表示，避免雪花 ID 超过 JavaScript `Number.MAX_SAFE_INTEGER` 后发生精度丢失。
