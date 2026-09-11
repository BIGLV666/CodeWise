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

## 调用约定

### 网关与直连地址

| 服务 | 端口 | 网关路由 | 说明 |
| --- | --- | --- | --- |
| `service-gateway` | `8082` | - | 推荐前端统一访问网关 |
| `service-user` | `8081` | `/api/user/**` | 用户、登录、头像接口 |
| `service-question` | `8084` | `/api/question/**` | 题目、测试点、提交、调试接口 |
| `service-review` | `8097` | `/api/review/**` | 收藏夹、复习、笔记与学习计划 |
| `service-community` | `8087` | `/api/community/**` | 帖子、题解、评论和点赞 |
| `service-message` | `8083` | `/api/message/**`、`/websocket/**` | 通知中心与 WebSocket 推送 |
| `service-ai` | `8085` | `/api/ai/**` | AI 建议、会话与自定义模型 |
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

### 统一响应结构

大多数接口返回 `Result<T>`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | `Integer` | 成功为 `200`，默认错误为 `400` |
| `message` | `String` | 成功默认为 `success` |
| `data` | `T` | 返回数据 |

成功示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}

```
注意如果返回结果只是一个字符串则message即返回结果

## 内部服务接口

以下接口也由 `@RestController` 暴露，但主要用于 Feign 服务间调用。

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

### Agent 专用接口

面向 codewise-agent 的接口与网页端控制器分离成类，经网关以用户 JWT 调用：

- `service-review`：`AgentFavoritesController`，基础路径 `/api/review/agent/favorites`，提供收藏夹瘦身 VO、批量增删题目、行锁跨夹移动、题目定位（详见 `docs/service-review/api.md`）。
- `service-question`：`AgentQuestionController`，`POST /api/question/agent/detail`，按 ID 批量返回完整题目详情，逐题做可见性判定（`ok`/`not_found`/`invisible`，私密题仅创建者与管理员可见，单次 ≤10 题）。
- `service-community`：`AgentCommunityController`，基础路径 `/api/community/agent`，提供帖子 latest 降序信息流/搜索/热榜/详情、发帖/编辑/删除、评论增删、显式终态点赞（幂等）、「我的内容」瘦身（详见 `docs/service-community/api.md`）。
- `service-review`：`AgentProgressTrackerController`，基础路径 `/api/review/agent/progress`，提供学习计划（进度计划追踪）的今日计划/active 列表/详情/日历概览，以及批量创建（幂等）/更新/删除；周报走网页端 `GET /api/review/progress/report/week`（前端与 agent 共用，读时聚合不落统计表）。状态机单向：未开始 → 进行中 →（已过期 或 已完成），已过期只能删除或新建。

### 查询用户信息

```http
GET /api/user/info/{userId}
Authorization: Bearer <token>
```

返回：`Result<UserDto>`。

说明：主要用于服务间查询用户资料。

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
| `roleId` | `Integer` | 角色，`1` 普通用户、`2` 管理员 |
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
