# CodeWise 后端接口总览

本文档只保留所有服务共用的调用约定、内部服务接口和 DTO 速查。各服务的业务接口已按服务拆分，避免同一接口在多个文档中重复维护。

## 服务接口目录

| 服务 | 文档 |
| --- | --- |
| `service-gateway` | [`service-gateway/README.md`](service-gateway/README.md) |
| `service-user` | [`service-user/api.md`](service-user/api.md) |
| `service-question` | [`service-question/api.md`](service-question/api.md) |
| `service-review` | [`service-review/api.md`](service-review/api.md) |
| `service-community` | [`service-community/api.md`](service-community/api.md) |
| `service-message` | [`service-message/README.md`](service-message/README.md) |
| `service-ai` | [`service-ai/README.md`](service-ai/README.md) |
| `service-judge` | [`service-judge/README.md`](service-judge/README.md) |
| `service-api` | [`service-api/README.md`](service-api/README.md) |
| `service-common` | [`service-common/README.md`](service-common/README.md) |
| `codewise-agent`（Python） | [`codewise-agent/README.md`](codewise-agent/README.md) |

## 调用约定

### 网关与直连地址

| 服务 | 端口 | 网关路由 | 说明 |
| --- | --- | --- | --- |
| `service-gateway` | `8082` | - | 推荐前端统一访问网关 |
| `service-user` | `8081` | `/api/user/**`、`/uploads/**` | 用户、登录、头像接口与头像静态资源 |
| `service-question` | `8084` | `/api/question/**` | 题目、测试点、提交、调试接口 |
| `service-review` | `8097` | `/api/review/**` | 收藏夹、复习、笔记与学习计划 |
| `service-community` | `8087` | `/api/community/**` | 帖子、题解、评论和点赞 |
| `service-message` | `8083` | `/api/message/**`、`/websocket/**` | 通知中心与 WebSocket 推送 |
| `service-ai` | `8085` | `/api/ai/**` | AI 建议、会话与自定义模型 |
| `service-quiz-nest` | `8088` | `/api/quiz/**` | 小组（建组/改组/退出与 root 晋升/加入/邀请码）与后续 Quiz 业务 |
| `service-judge` | `8086` | `/api/judge/**` | 容器池与失败提交管理（管理员） |

`codewise-agent`（FastAPI，8000）不经过网关路由：生产环境由 nginx 以 `/agentapi/` 反代，前端直连其 `/api/agent/**`（7 个端点，含 SSE 流式 `/api/agent/stream`），JWT 由 Agent 自行校验。

实时端点：WebSocket（STOMP）统一由 `service-message` 提供（网关 `/websocket/**` 路由）；SSE 为 `POST /api/ai/advice/ask`（题目内追问）与 `POST /api/agent/stream`（Agent 对话）。

通过网关调用示例：

```http
GET http://localhost:8082/api/question/cursorquestions?pageSize=20
Authorization: Bearer <token>
```

直连服务调用示例：

```http
GET http://localhost:8097/api/review/favorites/list
Authorization: Bearer <token>
```

### 鉴权说明

网关 `AuthGlobalFilter` 当前规则：

- 匿名放行使用精确路径白名单（`/api/user/login`、`/api/user/register`、邮箱登录/注册相关路径等），并去除结尾多余斜杠防止绕过；不做子串模糊匹配。
- `/uploads/**` 静态资源前缀放行。
- WebSocket 请求可通过 query 参数 `token` 或 `Authorization: Bearer <token>` 鉴权。
- 其他请求必须带：

```http
Authorization: Bearer <token>
```

网关验证后会向下游追加（内部 Token 经 `CODEWISE_INTERNAL_TOKEN` 环境变量配置，转发前会剥离客户端伪造的同名头）：

```http
X-User-Id: <userId>
X-User-Name: <userName>
X-Internal-Token: <环境变量注入，勿硬编码>
X-Real-IP: <clientIp>
```

业务服务通过 `UserContext.getUserId()` 读取当前用户。

### 管理员接口与限流

- 管理员接口以 `@RequireAdmin`（类级或方法级）标注，由 `service-common` 的 `AdminAuthAspect` 统一拦截：经 `UserFeignClient` 回查 `roleId`，`2` 管理员、`0` root 放行，其余返回「该功能仅限管理员使用」（`4030002`），未登录或回查失败返回「登录状态已失效，请重新登录」（`4010001`）。因此判断是否管理员接口应看代码里有没有该注解，而不是路径前缀。
- 对外接口普遍带 `@RateLimit(limit, window)`，由 api-governance 以 Redis 滑动窗口限流，超限返回中文提示与错误码 `4290001`。登录用户按用户维度计数（换 IP 无效），未登录按 IP 计数且配额放大 20 倍。常见档位：读接口 100–300/分，写接口 5–30/分。
- 各服务另有 `/api/<service>/governance` 管理端点（管理员）用于查看与调整运行期限流、日志配置。

### 统一响应结构

大多数接口返回 `Result<T>`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | `Integer` | 成功为 `200`；失败为七位业务错误码（见下表），**HTTP 状态码仍为 200** |
| `message` | `String` | 成功默认为 `success`；失败为面向用户的中文提示 |
| `data` | `T` | 返回数据 |

成功示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}

```
注意：`Result.success(String)` 是一个独立重载，会把这段文本写进 `message` 且 `data` 为 `null`。因此「只回一句提示语」的写接口（如收藏夹增删、复习项更新）前端要从 `message` 取文案，不要去 `data` 里找 `"success"`。

### 错误码与业务异常

编码规则：`code = 类别(3位) × 10000 + 服务号(2位) × 100 + 序号(2位)`，形如 `4040201`（题目服务 · 资源不存在）。

- **类别**取 HTTP 语义：`400` 参数/请求体、`401` 未登录、`403` 无权限、`404` 不存在、`409` 状态冲突/重复、`429` 频率限制、`500` 服务端。
- **服务号**：`00` 通用、`01` user、`02` question、`03` review、`04` community、`05` message、`06` ai、`07` judge、`08` 基础设施（service-common）、`09` gateway、`10` Python agent、`11` quiz-nest。

枚举分布（接口 `ErrorCode` 定义在 `service-api/.../exception/`）：

| 枚举 | 位置 | 数量 | 覆盖 |
| --- | --- | --- | --- |
| `CommonErrorCode` | `service-api` | 16 | 跨服务通用语义，优先复用 |
| `UserErrorCode` | `service-user` | 42 | 账号、角色、登录注册 |
| `QuestionErrorCode` | `service-question` | 59 | 题目、测试用例、提交与生成链路 |
| `ReviewErrorCode` | `service-review` | 49 | 复习项、收藏夹、笔记、进度计划 |
| `CommunityErrorCode` | `service-community` | 31 | 帖子、评论、点赞、申诉 |
| `MessageErrorCode` | `service-message` | 13 | 通知、事件认领、推送、小组邀请码 |
| `AiErrorCode` | `service-ai` | 48 | 模型调用、密钥、SSE、产物生成 |
| `JudgeErrorCode` | `service-judge` | 22 | 容器池、编译执行、重试与死信 |
| `InfraErrorCode` | `service-common` | 6 | 信封编解码、必需配置缺失 |
| `GatewayErrorCode` | `service-gateway` | 8 | 网关自身兜底（不依赖 service-api，文案与 `CommonErrorCode` 对齐） |
| `AgentErrorCode` | `codewise-agent` | 5 | Python agent 鉴权与会话 |
| `QuizNestErrorCode` | `service-quiz-nest` | 23 | 小组创建、修改、退出、加入与邀请码 |

收敛规则（写在 `service-common/.../advice/AbstractGlobalExceptionHandler`，各服务的 `Advice`/`advice` 子类只声明 `basePackages`）：

1. **只有 `BizException` 的消息面向用户原样返回**；其余异常一律换成通用中文文案，原始信息只进服务端日志。
2. `NullPointerException`、`DataAccessException`、Feign/IO 报错、下游服务的 `Result.message` 都**不得**出现在响应里——历史版本曾把 `"系统繁忙" + e.getMessage()` 直接回吐，等于把 SQL 与 JVM 提示泄漏到前端，现已由 `5000001`/`5000002`/`5000003` 兜底。
3. 抛出时用 `new BizException(枚举)`；需要携带原因用 `new BizException(枚举, cause)`，cause 只落日志；文案带变量用 `%s` 占位并由 args 填充（`new BizException(UserErrorCode.USER_BATCH_LIMIT, 50)`）。
4. 消息里禁止出现 SQL、下游原始报错、字段英文名与异常类名。
5. MQ 消费者按异常类型分流（毒消息/重试/死信）的 `catch` 分支保持原类型：`EnvelopeCodec` 解析失败仍抛 `IllegalArgumentException`，不要为了统一改成 `BizException` 而改变控制流。
6. api-governance 的过滤器拒绝（限流、`@RequireAdmin`）经 `GovernanceException` 归一到本段位：`4290001` 频率限制、`4010001` 未登录、`4030002` 仅管理员。`FilterContext.rejectStatus` 默认是 `429`，因此 `AdminAuthAspect` 必须显式改写为 401/403，否则权限类拒绝会被误判成限流码。

常用通用码：`4000001` 参数有误、`4000002` 缺少参数、`4000003` 请求体解析失败、`4010001` 登录状态已失效、`4030001` 无权操作、`4030002` 该功能仅限管理员使用、`4040001` 资源不存在、`4090001` 状态冲突、`4090002` 已重复提交、`4290001` 操作过于频繁、`5000001` 服务繁忙、`5000002` 数据操作失败、`5000003` 下游服务不可用。

## 内部服务接口

以下接口也由 `@RestController` 暴露，但主要用于 Feign 服务间调用。契约统一在 `service-api/.../feign/`：`UserFeignClient`、`QuestionFeignClient`、`AiArtifactFeignClient`，请求头由 `FeignRequestInterceptor` 自动透传身份与内部 Token。

### 查询单个题目信息

```http
GET /api/question/info/{questionId}
Authorization: Bearer <token>
```

返回：`Result<QuestionDto>`。

说明：`service-review` 添加收藏题目时会调用。

### 批量查询题目信息

```http
POST /api/question/info/favoritequestions
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：

```json
[1, 2, 3]
```

返回：`Result<List<QuestionDto>>`。

注意：这是 `POST + JSON 数组请求体`，仅供 Feign 内部调用。

### 批量查询题目瘦身信息

```http
POST /api/question/info/briefquestions
Content-Type: application/json
```

请求体：

```json
[1, 2, 3]
```

返回：`Result<List<QuestionBriefDto>>`（不含题干/样例等大字段，`status`/`createUserId` 供调用方做可见性过滤）。

注意：仅供 Feign 内部调用（`service-review` 收藏夹条目列表使用）。

### 查询单个用户信息

```http
GET /api/user/info/{userId}
Authorization: Bearer <token>
```

返回：`Result<UserDto>`。

说明：供服务间查询用户资料，`AdminAuthAspect` 也用它回查 `roleId` 判定管理员。

### 批量查询用户信息

```http
POST /api/user/info/list
Content-Type: application/json

[1, 2, 3]
```

返回：`Result<Map<Long, UserDto>>`。列表接口的用户昵称/头像走这一类批量查询，避免 N+1。

### 读取判题上下文

```http
GET /api/question/internal/judge-context/{judgeRecordId}
```

返回：`Result<JudgeContextDto>`（题面、测试点、语言与限额等判题所需数据）。

说明：由 `QuestionFeignClient.getJudgeContext` 暴露，鉴权为内部 Token 或管理员。

### 生成函数题 AI 产物

```http
POST /api/ai/internal/function-artifacts
X-CodeWise-Internal-Token: <与 CODEWISE_INTERNAL_TOKEN 同值>
Content-Type: application/json
```

返回：`Result<FunctionArtifactGenerateResponse>`。

说明：`service-question` 导入函数题时调用 `service-ai` 生成随机输入与标准答案产物。该端点除网关的内部 Token 外，还额外校验请求头 `X-CodeWise-Internal-Token` 与服务配置 `codewise.internal-token` 是否一致，两端必须使用同一个 `CODEWISE_INTERNAL_TOKEN`。产物落盘在 `data/function-artifacts/`（运行数据，不入库）。

### Agent 专用接口

面向 codewise-agent 的业务接口集中在各服务的 `Agent*Controller`，与网页端控制器分离，经网关以用户原始 JWT 调用：

- `service-review`：`AgentFavoritesController`，基础路径 `/api/review/agent/favorites`，提供收藏夹瘦身 VO、批量增删题目、行锁跨夹移动、题目定位（详见 `docs/service-review/api.md`）。
- `service-question`：`AgentQuestionController`，`POST /api/question/agent/detail`，按 ID 批量返回完整题目详情，逐题做可见性判定（`ok`/`not_found`/`invisible`，私密题仅创建者与管理员可见，单次 ≤10 题）。
- `service-community`：`AgentCommunityController`，基础路径 `/api/community/agent`，提供帖子 latest 降序信息流/搜索/热榜/详情、发帖/编辑/删除、评论增删、显式终态点赞（幂等）、「我的内容」瘦身（详见 `docs/service-community/api.md`）。
- `service-review`：`AgentProgressTrackerController`，基础路径 `/api/review/agent/progress`，提供学习计划（进度计划追踪）的今日计划/active 列表/详情/日历概览，以及批量创建（幂等）/更新/删除；周报走网页端 `GET /api/review/progress/report/week`（前端与 agent 共用，读时聚合不落统计表）。状态机单向：未开始 → 进行中 →（已过期 或 已完成），已过期只能删除或新建。
- `service-review`：`AgentNoteController`，基础路径 `/api/review/agent/note`，提供笔记与笔记文件夹的查询、创建、更新、删除与移动。

Agent 可调用的接口是白名单制：目标路径写死在 `codewise-agent/agent-runtime/.../gateway.ts` 中，新增 `Agent*` 接口后必须同步该白名单，否则工具调用会被拒绝。

## DTO 字段速查

### UserDto

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userId` | `String` | 用户 ID，按字符串返回以避免 JavaScript 精度丢失 |
| `userName` | `String` | 用户名 |
| `email` | `String` | 邮箱 |
| `phone` | `String` | 手机号 |
| `bio` | `String` | 个人简介 |
| `nickName` | `String` | 昵称 |
| `avatarUrl` | `String` | 头像 URL |
| `birthday` | `LocalDate` | 生日 |
| `roleId` | `Integer` | 角色，`1` 普通用户、`2` 管理员；`0` 为 root，管理员校验（`AdminAuthAspect`）把 `0` 与 `2` 一同放行 |
| `status` | `Integer` | 状态，`0` 禁用、`1` 启用、`2` 注销 |
| `openId` | `String` | 第三方登录 ID |
| `totalSubmit` | `Long` | 总提交数 |
| `totalAc` | `Long` | AC 数 |
| `rating` | `Long` | 评分 |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |
| `lastLoginIp` | `String` | 最近登录 IP |
| `lastLoginTime` | `LocalDateTime` | 最近登录时间 |
| `banTime` | `LocalDateTime` | 封禁截止时间 |

### Question / QuestionDto

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `questionId` | `Long` | 题目 ID |
| `title` | `String` | 标题 |
| `description` | `String` | 题面描述 |
| `inputDesc` | `String` | 输入说明 |
| `outputDesc` | `String` | 输出说明 |
| `sampleInput` | `String` | 样例输入 |
| `sampleOutput` | `String` | 样例输出 |
| `hint` | `String` | 提示 |
| `source` | `String` | 来源 |
| `difficulty` | `Integer` | 难度 |
| `tags` | `String` | 标签 |
| `timeLimit` | `Integer` | 时间限制 |
| `memoryLimit` | `Integer` | 内存限制 |
| `status` | `Integer` | 状态，`0` 下架、`1` 正常、`2` 审核、`3` 私密 |
| `aiStatue` | `String` | AI 处理状态 |
| `createUserId` | `Long` | 创建人 |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |
| `totalSubmit` | `Long` | 总提交数 |
| `totalAc` | `Long` | AC 数 |
| `passRate` | `BigDecimal` | 通过率 |
| `contentHash` | `String` | 内容哈希 |

### TestCase

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `caseId` | `Long` | 测试点 ID |
| `questionId` | `Long` | 题目 ID |
| `inputData` | `String` | 输入数据 |
| `expectedOutput` | `String` | 期望输出 |
| `isSample` | `Integer` | 是否样例，`1` 是、`0` 否 |
| `isHidden` | `Integer` | 是否隐藏，`1` 是、`0` 否 |
| `sortOrder` | `Integer` | 排序 |
| `scoreWeight` | `Integer` | 分值权重 |
| `timeLimit` | `Integer` | 时间限制 |
| `memoryLimit` | `Integer` | 内存限制 |
| `createUserId` | `Long` | 创建人 |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |

### SubmitRecord

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `submitRecordId` | `Long` | 提交记录 ID |
| `questionId` | `Long` | 题目 ID |
| `questionTitle` | `String` | 题目标题，冗余保存用于提交历史、复习记录展示 |
| `userId` | `Long` | 用户 ID |
| `submitTime` | `LocalDateTime` | 提交时间 |
| `submitContent` | `String` | 提交代码 |
| `submitStatus` | `String` | 提交状态 |
| `timeUsed` | `Integer` | 用时 |
| `memoryUsed` | `Integer` | 内存 |
| `JudgeStatus` | `String` | 判题状态 |
| `language` | `String` | 语言 |
| `createTime` | `LocalDateTime` | 创建时间 |

### Favorites

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `favoritesId` | `Long` | 收藏夹 ID |
| `favoritesName` | `String` | 收藏夹名称 |
| `favoritesType` | `String` | 收藏夹类型 |
| `favoritesContent` | `String` | 收藏夹描述 |
| `userId` | `Long` | 所属用户 ID |
| `questionIds` | `List<Long>` | 题目 ID 列表 |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |
