# service-community 社区服务接口与审核申诉

`service-community` 提供帖子、评论、回复和点赞能力，服务端口为 `8087`，基础路径为 `/api/community`。



```text
http://localhost:8082
```

除特别说明外，请求都需要携带登录令牌：

```http
Authorization: Bearer <token>
```

所有接口使用统一的 `Result<T>` 响应。帖子、评论以及用户 ID 均为 `Long`，前端应按字符串处理，避免 JavaScript `Number` 精度丢失。

## 请求 ID

发布帖子或评论前先获取请求 ID，用于防止网络重试造成重复写入。

```http
GET /api/community/request-id
Authorization: Bearer <token>
```

返回：`Result<String>`，将 `data` 原样传给发布接口的 `requestId` 参数。

## 帖子接口

### 发布帖子

```http
POST /api/community/posts?requestId=<requestId>
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`PostDto`

```json
{
  "postTitle": "二分查找边界怎么处理？",
  "postContent": "这里是帖子正文",
  "tags": ["二分", "算法"]
}
```

`userId` 和 `userName` 由登录态确定，客户端不需要传。标题最长 200 个字符，单个标签最长 20 个字符。

返回：`Result<Post>`。

### 帖子列表

```http
GET /api/community/posts?pageSize=20&lastId=100
Authorization: Bearer <token>
```

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `lastId` | `Long` | 否 | 首次不传，下一页传上次返回的 `nextCursor` |
| `pageSize` | `Integer` | 否 | 默认 20，范围 1 到 100 |

返回：`Result<CursorPageResult<HomePostVo>>`。帖子按 `postId` 升序返回。

### 帖子详情

```http
GET /api/community/posts/{postId}
Authorization: Bearer <token>
```

返回：`Result<PostVo>`，包括正文、当前用户是否点赞，以及按标签分组的相关帖子。缓存只保存与用户无关的帖子实体；`isLike` 始终按当前登录用户实时计算，避免将一个用户的点赞状态泄漏给其他用户。

## 评论接口

### 发布评论或回复

```http
POST /api/community/comments?requestId=<requestId>
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`CommentDto`

```json
{
  "comment": "可以用左闭右开区间统一边界。",
  "postId": "100",
  "rootCommentId": "200",
  "replyUserId": "300",
  "replyUserName": "alice"
}
```

`rootCommentId` 表示所属根评论；回复某个用户时再传 `replyUserId` 和 `replyUserName`。`requestId` 需先通过 `/api/community/request-id` 获取。

返回：`Result<Comment>`。

### 评论列表

```http
GET /api/community/comments?postId=100&rootCommentId=-1&pageSize=20
Authorization: Bearer <token>
```

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `postId` | `Long` | 是 | 帖子 ID（评论题解时为题解 ID） |
| `type` | `PostType` | 否 | 默认 `POST`；查询题解评论时传 `SOLUTION` |
| `rootCommentId` | `Long` | 否 | 默认 `-1` 不按根评论筛选；传根评论 ID 查询其回复 |
| `lastId` | `Long` | 否 | 下一页游标 |
| `pageSize` | `Integer` | 否 | 默认 20，范围 1 到 100 |

返回：`Result<CursorPageResult<CommentVo>>`，按 `commentId` 升序游标分页，只返回正常状态（`status=1`）的评论，每条记录包含当前用户的 `isLike` 状态。

## 点赞接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `PUT` | `/api/community/likes/posts/{postId}` | 切换帖子点赞状态 |
| `PUT` | `/api/community/likes/comments/{commentId}` | 切换评论点赞状态 |
| `PUT` | `/api/community/likes/solution/{solutionId}` | 切换题解点赞状态 |

返回 `true` 表示操作后已点赞，`false` 表示已取消。新增点赞后，社区服务异步生成点赞通知：

- 接收人从内容作者缓存获取，缓存未命中时回源对应业务表并回填。
- 自己点赞自己的内容不生成通知。
- 消息 ID 由业务类型、点赞用户和目标 ID 组成，同一用户对同一内容仅提醒一次。
- 通知发送到 `notification.exchange`（路由键 `notification.like.routing`），由 `service-message` 持久化并尝试 WebSocket 推送。
- 点赞计数仍通过 Redis 增量桶定时批量回写 MySQL。
- 通知任务提交到专用线程池 `communityLikeNotifyExecutor`，与点赞计数桶消费线程池 `communityBucketConsumerExecutor` 隔离（桶消费是长期占用线程的刷库循环，混用会饿死通知任务）；队列满时 `CallerRunsPolicy` 让通知退化为调用线程同步执行，通知不丢。

点赞接口为状态切换操作。返回 `true` 表示操作后已点赞，返回 `false` 表示操作后已取消点赞。

```http
PUT /api/community/likes/posts/{postId}
Authorization: Bearer <token>
```

返回：`Result<Boolean>`。

```http
PUT /api/community/likes/comments/{commentId}
Authorization: Bearer <token>
```

返回：`Result<Boolean>`。

```http
PUT /api/community/likes/solution/{solutionId}
Authorization: Bearer <token>
```

返回：`Result<Boolean>`。

点赞记录会先写入 Redis 增量桶，再由定时任务批量更新帖子、评论或题解的 `likeCount`，因此计数展示存在短暂延迟。

> 帖子详情的 `likeCount` 会实时叠加当前点赞增量桶中该帖的增量（读取 `like_post_key-<当前桶>`），因此发帖详情看到的点赞数已含尚未落库的部分。

## 异步线程池

社区服务注册三个专用 `ThreadPoolTaskExecutor`（均在 `AsyncThreadConfig` 中定义，参数可经 `application.yaml` 的 `codewise.async.*` 覆盖，拒绝策略统一为 `CallerRunsPolicy`，优雅关闭 `waitForTasksToCompleteOnShutdown=true`）：

| Bean 名称 | 用途 | 配置前缀 | 默认 core/max/queue | 线程名前缀 |
| --- | --- | --- | --- | --- |
| `communityCascadeDeleteExecutor` | 删帖/删评论的级联清理（标签、评论、点赞异步删除） | `codewise.async.cascade-delete` | 2 / 8 / 100 | `community-cascade-delete-` |
| `communityBucketConsumerExecutor` | 点赞桶、题解浏览量桶的刷库消费循环 | `codewise.async.bucket-consumer` | 2 / 4 / 100（无 keep-alive 项） | `community-bucket-consumer-` |
| `communityLikeNotifyExecutor` | 点赞通知（读缓存 + Feign 取用户 + 投递 RabbitMQ） | `codewise.async.like-notify` | 2 / 8 / 500 | `community-like-notify-` |

级联删除与桶消费为 `keep-alive` 60 秒、`await-termination` 30 秒；`bucket-consumer` 未配置 keep-alive。删除帖子的接口在级联任务被线程池拒绝时会在调用线程内同步执行兜底。

## Agent 专用社区接口

基础路径：`/api/community/agent`，供 codewise-agent（`AgentCommunityController`）调用，与网页端控制器分离成类。与网页端的差异：

- **公开信息流支持 latest 降序游标**（网页端只有升序），从最新帖子往回翻页；
- **点赞为显式终态（幂等）**：请求声明 `action=like/unlike`，服务端先查当前状态，已处于期望态直接短路返回 `changed=false`，避免网络重试导致赞状态震荡（网页端是「切换」语义）；
- **发帖/发评论重复 requestId 显式返回 `duplicate=true`**（网页端重复请求返回 `data=null`）；
- **「我的内容」正文截断**到 200 字符（完整正文经帖子详情接口获取）；
- 路径参数统一用 query 表达，适配 agent 工具的静态路径白名单。

### 帖子信息流（latest/oldest）

- `GET /api/community/agent/posts?lastId=&pageSize=&order=latest|oldest`（默认 latest，pageSize 1-100）
- 返回 `Result<CursorPageResult<HomePostVo>>`（仅 status=1 的帖子）

### 热榜 / 搜索 / 详情

- `GET /api/community/agent/posts/hot` → 热榜（≤10 条）
- `GET /api/community/agent/posts/search?keyword=&tag=&limit=` → 标题关键词或完整标签（二者必须且只能提供一个）
- `GET /api/community/agent/post?postId=` → 详情（正文/标签/点赞状态/相关推荐；待审核/已下架仅作者可见）

### 发帖 / 编辑 / 删除

- `POST /api/community/agent/post` body `AgentPostCreateDto{postTitle, postContent, tags, requestId}` → `AgentCreatedVo{duplicate, postId, status}`（新帖 status=0 待审核）
- `PUT /api/community/agent/post?postId=` body `AgentPostUpdateDto{postTitle, postContent, tags}` → `AgentContentStatusVo{id, status=0, note}`（编辑后重新进入待审核）
- `DELETE /api/community/agent/post?postId=` → `Result<String>`（硬删除，仅作者；评论/点赞异步级联清理）

### 评论

- `GET /api/community/agent/comments?postId=&lastId=&pageSize=&rootCommentId=` → `CursorPageResult<CommentVo>`（仅 POST 类型）
- `POST /api/community/agent/comments` body `AgentCommentCreateDto{comment, postId, rootCommentId?, replyUserId?, replyUserName?, requestId}` → `AgentCreatedVo{duplicate, commentId, postId, status=1}`（评论即时可见）
- `DELETE /api/community/agent/comments?commentId=` → `Result<String>`（根评论级联删回复）

### 点赞（显式终态）

- `POST /api/community/agent/likes` body `AgentLikeRequestDto{targetType: POST|COMMENT, targetId, action: like|unlike}` → `AgentLikeResultVo{liked, changed}`

### 我的内容（瘦身）

- `GET /api/community/agent/mine?type=POST|COMMENT|SOLUTION&lastId=&pageSize=` → `CursorPageResult<MyContentVo>`（正文截断，附 status/rejectReason）

## DTO 字段

### PostDto

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `postTitle` | `String` | 帖子标题 |
| `postContent` | `String` | 帖子正文 |
| `tags` | `List<String>` | 标签列表 |
| `userId` | `Long` | 服务端从登录态读取，客户端忽略 |
| `userName` | `String` | 服务端从登录态读取，客户端忽略 |

### CommentDto

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `comment` | `String` | 评论内容 |
| `postId` | `Long` | 所属帖子 ID |
| `rootCommentId` | `Long` | 所属根评论 ID |
| `replyUserId` | `Long` | 被回复用户 ID，可为空 |
| `replyUserName` | `String` | 被回复用户名，可为空 |

### CursorPageResult

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `records` | `List<T>` | 当前页记录 |
| `nextCursor` | `Long` | 下一页游标，没有下一页时为 `null` |
| `hasNext` | `Boolean` | 是否还有下一页 |
| `total` | `Long` | 当前游标分页不统计总数，通常为 `null` |

### HomePostVo

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `postId` | `Long` | 帖子 ID |
| `postTitle` | `String` | 标题 |
| `tags` | `List<String>` | 标签 |
| `userId` | `String` | 发布用户 ID（实体为 `Long`，VO 中转成字符串避免前端精度丢失） |
| `userName` | `String` | 发布用户昵称（经 Feign 回填） |
| `avatar` | `String` | 发布用户头像（经 Feign 回填） |
| `likeCount` | `Long` | 点赞数 |
| `commentCount` | `Long` | 评论数 |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |

## 热点与搜索接口

### 热点帖子

```http
GET /api/community/posts/hot
Authorization: Bearer <token>
```

返回：`Result<List<HomePostVo>>`，最多返回 10 条，按照热度从高到低排列。

热点分数综合帖子点赞数、评论数和发布时间计算。Redis 使用 ZSet 保存排名，每 5 分钟先以临时 Key 根据数据库计数和时间衰减完成重建，再合并重建期间增量并通过 Lua 原子切换；点赞和评论操作会在重算间隔内实时调整分数，读请求不会因重建短暂读到空榜。

### 标题模糊搜索

```http
GET /api/community/posts/search?keyword=二分&limit=20
Authorization: Bearer <token>
```

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyword` | `String` | 是 | 标题关键词，去除首尾空格后最长 50 个字符 |
| `limit` | `Integer` | 否 | 默认 20，范围 1 到 100 |

返回：`Result<List<HomePostVo>>`。只查询正常状态的帖子，按照发布时间倒序返回。

### 标签搜索

```http
GET /api/community/posts/tag?tag=动态规划&limit=20
Authorization: Bearer <token>
```

标签使用完整名称匹配。返回：`Result<List<HomePostVo>>`，只包含正常状态的帖子，并按照发布时间倒序排列。

## 修改与删除接口

### 修改帖子

```http
PUT /api/community/posts/{postId}
Authorization: Bearer <token>
Content-Type: application/json
```

请求体使用 `PostVo` 中的以下字段，其他字段无需提交：

```json
{
  "postTitle": "修改后的标题",
  "postContent": "修改后的正文",
  "tags": ["Java", "并发"]
}
```

只有帖子发布者可以修改。路径中的 `postId` 是唯一目标 ID，服务端不会使用客户端提交的 `userId` 判断权限。标题最长 200 个字符，单个标签少于 20 个字符，重复标签会被合并。帖子和标签在同一事务中更新。

### 删除帖子

```http
DELETE /api/community/posts/{postId}
Authorization: Bearer <token>
```

只有帖子发布者可以删除。帖子主体删除后，相关标签、评论、帖子点赞和评论点赞记录由受控线程池异步级联清理，同时从热点榜和帖子缓存中移除；任务状态会记录为 `pending`、`success` 或 `failed` 以便排查。

### 删除评论

```http
DELETE /api/community/comments/{commentId}
Authorization: Bearer <token>
```

只有评论发布者可以删除。删除根评论时会异步删除该评论下的回复以及对应点赞记录，并同步调整帖子评论数和热点分数。

异步删除状态会短暂保存在 Redis 中。接口返回成功表示删除任务已经受理；若异步级联 SQL 失败，服务会记录错误并将任务状态标记为 `failed`。

## 题解接口

题解使用 `/api/community/solutions`，题解标签和评论通过 `type=SOLUTION` 与普通社区帖子隔离。

```http
POST /api/community/solutions
GET /api/community/solutions?questionId=100&pageSize=20&lastId=200
GET /api/community/solutions/{solutionId}
PUT /api/community/solutions/{solutionId}
DELETE /api/community/solutions/{solutionId}
```

发布和修改题解的请求体：

```json
{
  "questionId": "100",
  "solutionTitle": "动态规划状态设计",
  "solutionContent": "题解正文",
  "tags": ["动态规划", "状态机"]
}
```

查询题解详情会累计一次浏览量。浏览增量使用 Redis 双桶记录，定时任务通过 Lua 原子切换桶并批量回写 MySQL。修改和删除操作仅允许题解作者执行；删除题解时会清理该题解的标签、评论和评论点赞。题解详情的 `isLike` 仅查询当前登录用户对应的点赞记录。

评论社区帖子时 `type` 可以省略；评论题解时必须传 `SOLUTION`：

```json
{
  "comment": "这里的状态转移可以再解释一下吗？",
  "postId": "200",
  "type": "SOLUTION"
}
```

查询题解评论：

```http
GET /api/community/comments?postId=200&type=SOLUTION&pageSize=20
```

### PostVo

`PostVo` 在 `HomePostVo` 的主要帖子字段基础上增加：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `postContent` | `String` | 帖子正文 |
| `isLike` | `Boolean` | 当前用户是否已点赞 |
| `userDto` | `UserDto` | 作者信息（经 Feign 获取，可能为 `null`） |
| `relatedPost` | `Map<String, Map<Long, String>>` | 标签到相关帖子 ID、标题的映射 |

### CommentVo

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `commentId` | `Long` | 评论 ID |
| `comment` | `String` | 评论内容 |
| `userId` | `String` | 评论用户 ID（VO 中转成字符串） |
| `userName` | `String` | 评论用户名 |
| `postId` | `Long` | 所属帖子/题解 ID |
| `rootCommentId` | `Long` | 所属根评论 ID |
| `replyUserId` | `Long` | 被回复用户 ID |
| `replyUserName` | `String` | 被回复用户名 |
| `likeCount` | `Long` | 点赞数 |
| `isLike` | `Boolean` | 当前用户是否已点赞 |
| `type` | `PostType` | 评论目标类型：`POST` 或 `SOLUTION` |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |

---

## 内容审核与申诉 API

## 概述

本文档描述 CodeWise 平台社区模块的内容审核和申诉功能 API，涵盖帖子（POST）、评论（COMMENT）、题解（SOLUTION）三种内容类型。

**服务**: `service-community` (端口 8087)  
**网关路由**: 所有请求通过 `service-gateway` (8082) 转发，需携带 JWT Token

---

## 一、内容审核 API（管理员专用）

**权限要求**: 所有审核接口需要 `@RequireAdmin` 权限  
**基础路径**: `/api/community/check`

### 1.1 获取待审核帖子列表

```http
GET /api/community/check/post/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标，上一页最后一条的 post_id |
| pageSize | Integer | 否 | 每页数量，默认 10 |

**响应示例**（`CursorPageResult<HomePostVo>`，帖子按 `postId` 升序游标分页，仅含 `status=0` 待审核帖子；`total` 未统计，恒为 `null`）:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "postId": 123456,
        "postTitle": "如何优化算法性能",
        "tags": ["算法"],
        "userId": "1001",
        "userName": "张三",
        "avatar": "https://...",
        "likeCount": 0,
        "commentCount": 3,
        "createTime": "2026-08-18T10:30:00",
        "updateTime": "2026-08-18T10:30:00"
      }
    ],
    "nextCursor": 123456,
    "hasNext": true,
    "total": null
  }
}
```

### 1.2 获取帖子审核详情

```http
GET /api/community/check/post/detail?postId=123456
```

**响应示例**（`CheckDetailVo`，帖子/评论/题解共用同一扁平结构，作者信息经 Feign 回填到 `userDto`）:
```json
{
  "code": 200,
  "data": {
    "targetId": 123456,
    "type": "POST",
    "title": "如何优化算法性能",
    "content": "正文...",
    "userId": "1001",
    "userDto": { "userId": "1001", "userName": "zhangsan", "nickName": "张三", "avatarUrl": "https://...", "roleId": 1 },
    "status": 0,
    "tags": ["算法"],
    "postId": null,
    "rootCommentId": null,
    "questionId": null,
    "likeCount": 0,
    "commentCount": 3,
    "createTime": "2026-08-18T10:30:00",
    "updateTime": "2026-08-18T10:30:00"
  }
}
```

### 1.3 审核待审核帖子

```http
POST /api/community/check/post?postId=123456&status=1
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| postId | Long | 是 | 帖子 ID |
| status | Integer | 是 | 1=通过，2=拒绝（仅待审核 `status=0` 的帖子可审核） |
| reason | String | 否 | 拒绝原因，拒绝时写入 `take_down_post` 并随通知下发 |

通过后帖子入热榜（Redis ZSet 记 0 分），拒绝时移出热榜并记录下架表；无论通过或拒绝都会向作者发送审核结果通知。

**响应**:
```json
{
  "code": 200,
  "data": null
}
```

### 1.4 下架或恢复已发布帖子

```http
POST /api/community/check/post/status
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| postId | Long | 是 | 帖子 ID |
| status | Integer | 是 | 1=恢复，2=下架 |
| reason | String | 否 | 下架原因（下架时建议填写） |

**响应**:
```json
{
  "code": 200,
  "data": null
}
```

---

### 1.5 获取评论列表（用于下架处置）

评论采用即时发布，没有待审核态；该列表返回**已发布（`status=1`）** 的评论，供管理员执行下架处置。

```http
GET /api/community/check/comment/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标，上一页最后一条的 comment_id |
| pageSize | Integer | 否 | 每页数量，默认 10 |
| rootCommentId | Long | 否 | 筛选某个一级评论下的回复 |
| type | PostType | 否 | 默认按 `POST`，可传 `SOLUTION` |

**响应示例**（`CursorPageResult<CommentVo>`，按 `commentId` 升序游标；审核台不回填 `isLike`）:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "commentId": 789,
        "comment": "评论内容...",
        "userId": "1002",
        "userName": "李四",
        "postId": 123456,
        "rootCommentId": null,
        "replyUserId": null,
        "replyUserName": null,
        "likeCount": 0,
        "isLike": null,
        "type": "POST",
        "createTime": "2026-08-18T11:00:00",
        "updateTime": "2026-08-18T11:00:00"
      }
    ],
    "nextCursor": 789,
    "hasNext": false,
    "total": null
  }
}
```

### 1.6 获取评论审核详情

```http
GET /api/community/check/comment/detail?commentId=789
```

### 1.7 下架或恢复评论

```http
POST /api/community/check/comment/status
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| commentId | Long | 是 | 评论 ID |
| status | Integer | 是 | 1=恢复，2=下架 |
| reason | String | 否 | 下架原因 |

**说明**: 评论采用即时发布，无待审核态，因此没有「审核通过/拒绝」接口。

---

### 1.8 获取待审核题解列表

```http
GET /api/community/check/solution/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标 |
| pageSize | Integer | 否 | 每页数量，默认 10 |

**响应示例**（`CursorPageResult<HomeSolutionVo>`，按 `solutionId` 升序游标；`HomeSolutionVo` 不含正文与状态字段，作者信息经 Feign 回填到 `userDto`）:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "solutionId": 5001,
        "questionId": 101,
        "solutionTitle": "双指针解法",
        "solutionUserId": "1003",
        "userDto": { "userId": "1003", "userName": "lisi", "nickName": "李四", "avatarUrl": "https://...", "roleId": 1 },
        "likeCount": 0,
        "lookCount": 12,
        "commentCount": 0,
        "tags": ["双指针"],
        "createTime": "2026-08-18T12:00:00",
        "updateTime": "2026-08-18T12:00:00"
      }
    ],
    "nextCursor": 5001,
    "hasNext": true,
    "total": null
  }
}
```

### 1.9 获取题解审核详情

```http
GET /api/community/check/solution/detail?solutionId=5001
```

### 1.10 审核待审核题解

```http
POST /api/community/check/solution?solutionId=5001&status=1
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| solutionId | Long | 是 | 题解 ID（仅待审核 `status=0` 的题解可审核） |
| status | Integer | 是 | 1=通过，2=拒绝 |
| reason | String | 否 | 拒绝原因，拒绝时写入 `take_down_post` 并随通知下发 |

### 1.11 下架或恢复已发布题解

```http
POST /api/community/check/solution/status
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| solutionId | Long | 是 | 题解 ID |
| status | Integer | 是 | 1=恢复，2=下架 |
| reason | String | 否 | 下架原因 |

---

### 1.12 通用审核详情查询

```http
GET /api/community/check/detail?type=POST&targetId=123456
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | PostType | 是 | POST/COMMENT/SOLUTION |
| targetId | Long | 是 | 对应内容的 ID |

**响应**: 同各类型的详情接口

---

### 1.13 获取下架记录列表

```http
GET /api/community/check/takedown/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标，传上一页返回的 `nextCursor`；本页按记录 `id` **降序**返回 |
| pageSize | Integer | 否 | 每页数量，默认 10 |

**响应示例**（`CursorPageResult<TakeDownPostVo>`；`takeDownId` 是 `take_down_post` 的雪花 ID，用 `@JsonSerialize(ToStringSerializer)` 序列化为字符串避免精度丢失；`adminName` 取管理员 `userName`；`title`/`content` 为关联内容摘要，正文超 100 字截断加 `...`；`total` 为本页条数）:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "takeDownId": "1948273645123456789",
        "rootType": "POST",
        "rootId": 123456,
        "rootCommentId": null,
        "questionId": null,
        "reason": "涉及敏感内容",
        "adminId": 1,
        "adminName": "admin",
        "createTime": "2026-08-18T14:00:00",
        "title": "如何优化算法性能",
        "content": "我在做题时..."
      }
    ],
    "nextCursor": 1948273645123456789,
    "hasNext": false,
    "total": 3
  }
}
```

---

## 二、申诉管理 API（管理员专用）

**权限要求**: `@RequireAdmin`  
**基础路径**: `/api/community/appeal-admin`

### 2.1 获取申诉列表

```http
GET /api/community/appeal-admin/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标，上一页最后一条的 appeal_id |
| pageSize | Integer | 否 | 每页数量，默认 20 |
| status | Integer | 否 | 筛选状态：0=待处理，1=已拒绝，2=已恢复，null=全部 |

**响应示例**（`CursorPageResult<AppealVo>`，按 `appealId` 升序游标；管理端回填申诉人 `user` 与关联内容 `title`；`total` 为本页条数）:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "appealId": 2001,
        "postId": 123456,
        "postType": "POST",
        "title": "如何优化算法性能",
        "reason": "我认为内容没有违规...",
        "takeDownReason": null,
        "status": 0,
        "user": { "userId": "1001", "userName": "zhangsan", "nickName": "张三", "avatarUrl": "https://...", "roleId": 1 },
        "createTime": "2026-08-18T15:00:00",
        "updateTime": "2026-08-18T15:00:00"
      }
    ],
    "nextCursor": 2001,
    "hasNext": true,
    "total": 12
  }
}
```

### 2.2 处理申诉

```http
POST /api/community/appeal-admin/handle
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| appealId | Long | 是 | 申诉 ID |
| pass | Boolean | 是 | true=通过并恢复内容，false=拒绝申诉 |
| adminReason | String | 否 | 管理员回复说明 |

**响应**:
```json
{
  "code": 200,
  "data": null
}
```

**业务逻辑**:
- `pass=true`：申诉状态置为 `2`（已恢复），原内容状态恢复为 `1`（正常）；帖子同时清缓存并移出热榜。
- `pass=false`：申诉状态置为 `1`（已拒绝），`takeDownReason` 记为 `adminReason`，原内容状态保持不变。
- 仅处理中（申诉 `status=0`）的记录可被处理，重复处理返回「该申诉已处理」。
- 处理结果通知（`NotificationCenterType.APPEAL`）与申诉状态更新**同事务**经 OutboxPro 事务性发件箱登记（`outboxPublisher.publish(NOTIFICATION_APPEAL, ...)`），由 Relay 经 Publisher Confirm 至少一次投递；重复投递由 `service-message` 按 `messageId` 幂等去重。

---

## 三、用户申诉 API

**基础路径**: `/api/community/appeal`

### 3.1 提交申诉

```http
POST /api/community/appeal/submit
```

**请求体**（与后端 `AppealDto` 字段一致）:
```json
{
  "postId": 123456,
  "postType": "POST",
  "reason": "我认为我的帖子没有违规，请重新审核..."
}
```

**字段说明**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| postId | Long | 是 | 内容 ID（帖子/评论/题解的 ID） |
| postType | String | 是 | POST/COMMENT/SOLUTION |
| reason | String | 是 | 申诉理由 |

**响应**:
```json
{
  "code": 200,
  "data": null
}
```

**说明**: 
- 只能申诉本人发布的内容，且内容处于被下架/被拒绝状态（status=2/3）时才可申诉
- 同一内容只有一条 `Appeal` 记录：待审核（status=0）期间重复提交会被拒绝；处理完结后可再次提交并更新理由、重置状态
- 申诉状态：0-待审核，1-拒绝，2-恢复（通过后原内容恢复）
- 提交申诉**不再向管理员发送通知**，管理员通过「申诉管理列表」轮询待处理（status=0）申诉

### 3.2 我的申诉历史

```http
GET /api/community/appeal/my?lastId=&pageSize=10
```

**参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标：上一页返回的最大 appealId，首页省略 |
| pageSize | Integer | 否 | 页大小，默认 10，上限 50 |

**响应**: `data` 为游标分页结构（按 appealId 升序）：
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "appealId": 7,
        "postId": 123456,
        "postType": "POST",
        "title": "帖子标题",
        "reason": "误判，内容未违规",
        "takeDownReason": "管理员回复/下架原因",
        "status": 0,
        "user": null,
        "createTime": "2026-09-11T10:00:00",
        "updateTime": "2026-09-11T10:00:00"
      }
    ],
    "nextCursor": 7,
    "hasNext": true,
    "total": 1
  }
}
```

**说明**: 仅返回当前登录用户的申诉（身份取自网关注入的用户上下文）；`records[].user` 恒为 null（查自己的申诉不回填申诉人信息）。

### 3.3 申诉详情

```http
GET /api/community/appeal/{appealId}
```

**响应**: `data` 为单条 `AppealVo`（结构同 3.2 中 records 元素）。

**说明**: 仅申诉发起人可查看；查询他人申诉返回错误「无权查看该申诉」，不存在返回「申诉不存在」。

---

## 四、我的内容 API（用户）

**基础路径**: `/api/community/mine`

### 4.1 查看我的内容

```http
GET /api/community/mine?type=POST&lastId=&pageSize=20
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | PostType | 是 | POST/COMMENT/SOLUTION |
| lastId | Long | 否 | 游标 |
| pageSize | Integer | 否 | 每页数量，默认 20 |

**响应示例**（`CursorPageResult<MyContentVo>`；`targetId`/`rootId`/`questionId` 为字符串；列表按 ID **降序**游标分页，`nextCursor` 传下一页 `lastId`；`rejectReason` 从 `take_down_post` 回填（仅下架/拒绝内容）；`total` 恒为 `null`）:
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "targetId": "123457",
        "type": "POST",
        "title": "另一个帖子",
        "content": "...",
        "status": 2,
        "rejectReason": "内容涉及违规",
        "tags": ["算法"],
        "rootId": null,
        "rootType": null,
        "questionId": null,
        "createTime": "2026-08-17T09:00:00",
        "updateTime": "2026-08-17T09:00:00"
      },
      {
        "targetId": "123456",
        "type": "POST",
        "title": "如何优化算法性能",
        "content": "我在做题时...",
        "status": 1,
        "rejectReason": null,
        "tags": ["二分"],
        "rootId": null,
        "rootType": null,
        "questionId": null,
        "createTime": "2026-08-18T10:30:00",
        "updateTime": "2026-08-18T10:30:00"
      }
    ],
    "nextCursor": 123456,
    "hasNext": true,
    "total": null
  }
}
```

**状态码说明**:
| status | 说明 |
|--------|------|
| 0 | 待审核 |
| 1 | 已通过/正常 |
| 2 | 已拒绝/已下架 |

---

## 五、数据模型

### 5.1 内容状态 (PostStatus)

`PostStatus` 不是枚举，而是保存 `int` 常量的工具类（数据库为 tinyint，保持既有映射）：`PENDING=0`（待审核，仅作者与管理员可见）、`NORMAL=1`（审核通过，公开可见）、`TACK_DOWN=2`（已下架/审核不通过）。帖子 `Post` 实体另有 `status=3`（审核未通过）的历史取值；申诉资格判断同时接受 `2` 和 `3`。

```java
public final class PostStatus {
    public static final int PENDING = 0;   // 待审核
    public static final int NORMAL  = 1;   // 审核通过
    public static final int TACK_DOWN = 2; // 已下架 / 审核不通过
}
```

### 5.2 内容类型枚举 (PostType)

```java
public enum PostType {
    POST,       // 社区帖子
    SOLUTION,   // 题解
    COMMENT     // 评论
}
```

`PostType` 用 `@EnumValue`/`@JsonValue`，与数据库及 JSON 均以枚举名（`POST`/`SOLUTION`/`COMMENT`）存取。

### 5.3 申诉状态

申诉没有独立枚举类，`Appeal.status` 为 `int`：`0`-待审核、`1`-拒绝、`2`-恢复（通过后原内容恢复）。

---

## 六、通知机制

### 6.1 审核通知

**触发场景**: 管理员审核内容后（通过 / 拒绝 / 下架 / 恢复）

**通知对象**: 内容所有者（作者）

**载荷**: `NotificationDto`（`type=NotificationCenterType.CHECKED`），`extraData` 为 `NotificationCheckedDto{passed, takeDown, title, message, rootId, rootType, rootCommentId, questionId}`；文案由 `service-message` 消费端生成。`messageId` 形如 `content-check:<类型>:<id>:<动作码>`（动作码 pass/reject/down/restore），保证下架后恢复不会被误判为重复。

**投递方式**: 尽力而为（fire-and-forget），`PostCheckService` 在事务外用 `RabbitTemplate` 直发 `notification.exchange` → `notification.checked.routing`，发送失败不影响审核结果落库。

### 6.2 申诉通知

**用户提交申诉**：**不再向管理员发送通知**，管理员通过「申诉管理列表」轮询待处理（`status=0`）申诉。

**管理员处理申诉 → 通知用户**

- **通知对象**: 申诉人
- **通知类型**: `NotificationCenterType.APPEAL`，`extraData` 为 `NotificationAppealDto{appealId, postId, postType, title, reason, adminReason, passed}`
- **投递方式**: 经 **OutboxPro 事务性发件箱**与申诉状态更新同事务登记（`outboxPublisher.publish(EventTypes.NOTIFICATION_APPEAL, notification)`），由 Relay 经 Publisher Confirm 至少一次投递；路由仍为 `notification.exchange` → `notification.appeal.routing`（见 `OutboxEventRouteConfig`）
- **幂等**: 生产者不再做 Redis 预检占位；`messageId` 为 `appeal-handle:<appealId>:<pass|reject>`，重复投递由消费端按 `messageId` 去重

---

## 七、数据库表结构

### 7.1 appeal（申诉表）

```sql
CREATE TABLE appeal(
    appeal_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    post_type VARCHAR(20) NOT NULL,
    user_id BIGINT NOT NULL,
    reason TEXT,
    take_down_reason TEXT,
    status INT NOT NULL,
    admin_user_id BIGINT,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_post_id_status(user_id,post_id,post_type,status),
    INDEX idx_post_id(post_id,user_id,post_type,status),
    INDEX idx_type_status(post_type,status)
);
```

### 7.2 take_down_post（下架记录表）

```sql
CREATE TABLE take_down_post(
    id BIGINT NOT NULL PRIMARY KEY COMMENT '主键，雪花 ID（IdType.ASSIGN_ID，非自增；VO 序列化为字符串）',
    root_type VARCHAR(20) NOT NULL COMMENT '内容类型：POST/SOLUTION/COMMENT',
    root_id BIGINT NOT NULL COMMENT '根内容ID（帖子/题解/评论的ID）',
    root_comment_id BIGINT COMMENT '如果是评论，记录评论ID',
    question_id BIGINT COMMENT '关联题目ID（题解时使用）',
    reason TEXT NOT NULL COMMENT '下架原因',
    admin_id BIGINT NOT NULL COMMENT '操作管理员ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_root(root_type, root_id),
    INDEX idx_admin(admin_id),
    INDEX idx_create_time(create_time)
);
```

### 7.3 post/comment/solution 的状态与原因字段

帖子、评论、题解各自使用整型 `status` 列表示内容状态（`0` 待审核 / `1` 正常 / `2` 下架，帖子另有历史值 `3` 审核未通过），实体与 mapper 均按整型映射，**不存在 `check_reason` 列**。审核拒绝 / 下架的原因统一写入 `take_down_post.reason`；「我的内容」列表的 `rejectReason` 由 `MyContentService` 按内容类型反查 `take_down_post`（取最新一条）回填。

---

## 八、权限要求

### 8.1 管理员权限

`PostCheckController` 与 `AppealAdminController` 类级标注 `@RequireAdmin`（`/api/community/check/**`、`/api/community/appeal-admin/**`）。校验由 `service-common` 的 `AdminAuthAspect` 完成——它实现 api-governance 的 `PreFilter`，通过 `META-INF/spring/...AutoConfiguration.imports` 全局自动注册，凡引入 service-common 的服务都生效。判定流程：取 `UserContext.getUserId()`（网关注入，不信任客户端），经 `UserFeignClient.getUserInfo` 回查 `roleId`，`roleId==2`（管理员）或 `0`（root）放行；否则通过 `FilterContext.setRejectStatus` + `setRejectReason` 返回拒绝，文案取自 `CommonErrorCode`——未登录或回查失败 → 「登录状态已失效，请重新登录」（`4010001`），非管理员 → 「该功能仅限管理员使用」（`4030002`）。`FilterContext.rejectStatus` 默认值是 `429`（面向限流），本切面必须显式改写为 401/403，否则权限类拒绝会被归一成限流码 `4290001`。**并非**读取 JWT 里的 role 字段。

### 8.2 用户权限

所有接口都要求登录：网关 `AuthGlobalFilter` 校验 JWT 后注入 `X-User-Id` 等身份头，下游 `UserAuthInterceptor` 校验内部令牌并填充 `UserContext`（`WebConfig` 对其注册 `/**` 拦截）。发帖、评论、点赞、删除、我的内容/申诉等接口一律从 `UserContext.getUserId()` 取当前用户，不接受客户端提交的 `userId`。

### 8.3 限流

各接口经 api-governance `@RateLimit`（Redis 滑动窗口）限流，超限返回错误码 `4290001`（HTTP 仍为 `200`），提示「社区操作过于频繁，请稍后重试」。不同接口阈值不同，例如发帖/编辑/删除 20/60s、点赞 120/60s、列表查询 100–200/60s，审核台类级 120/60s。

---

## 九、错误码

| code | HTTP | 说明 |
|------|------|------|
| 200 | 200 | 成功（`Result.success`） |
| `4000401`、`4040401`、`4090401`、`5000401` 等 | 200 | 社区自有业务错误：`throw new BizException(CommunityErrorCode.X)`，编码规则 `类别(3位) × 10000 + 服务号(04) × 100 + 序号(2位)`，`message` 取枚举里面向用户的文案 |
| `4000001`、`4010001`、`4030001`、`4040001`、`5000001` 等 | 200 | 跨服务通用语义（参数、未登录、无权限、资源不存在、兜底）复用 `service-api` 的 `CommonErrorCode`，不在本服务重复定义 |
| `4290001` | 200 | api-governance 限流拒绝，提示「社区操作过于频繁，请稍后重试」（配置项 `api.governance.rate-limit.status-code: 429` 只用于推导错误码，响应 HTTP 仍为 200） |
| `4010001` | 401 | 由 `service-gateway` 的 `AuthGlobalFilter` 在 JWT 校验阶段直接短路返回（响应体同为 `{code, message, data}`，文案取自 `GatewayErrorCode`），请求不会到达本服务 |
| 任意非 200 的 code | 200 | 其余异常（NPE、SQL/`DataAccessException`、Feign 与下游原始报错）由 `service-common` 的 `AbstractGlobalExceptionHandler` 统一换成通用中文文案，原始信息只进服务端日志；`IllegalArgumentException` / `IllegalStateException` 同样返回枚举通用文案，不透出 `e.getMessage()` |

业务服务的 HTTP 状态码保持 200，失败与否一律看 `Result.code`（前端按 `code !== 200` 分支）。

管理员权限拒绝（`AdminAuthAspect`）经 `GovernanceException` 由 `handleGovernance` 按拒绝状态归一：401 → `4010001`「登录状态已失效，请重新登录」，403 → `4030002`「该功能仅限管理员使用」。完整枚举清单见 `docs/backend-controller-api.md`「错误码与业务异常」。

**错误响应示例**:
```json
{
  "code": 4030002,
  "message": "该功能仅限管理员使用",
  "data": null
}
```

---

## 十、使用示例

### 10.1 管理员审核帖子流程

1. 获取待审核帖子列表
```bash
curl -X GET "http://localhost:8082/api/community/check/post/list?pageSize=10" \
  -H "Authorization: Bearer {admin_token}"
```

2. 查看帖子详情
```bash
curl -X GET "http://localhost:8082/api/community/check/post/detail?postId=123456" \
  -H "Authorization: Bearer {admin_token}"
```

3. 审核通过
```bash
curl -X POST "http://localhost:8082/api/community/check/post?postId=123456&status=1" \
  -H "Authorization: Bearer {admin_token}"
```

### 10.2 用户申诉流程

1. 查看我的内容（发现被拒绝）
```bash
curl -X GET "http://localhost:8082/api/community/mine?type=POST" \
  -H "Authorization: Bearer {user_token}"
```

2. 提交申诉
```bash
curl -X POST "http://localhost:8082/api/community/appeal/submit" \
  -H "Authorization: Bearer {user_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "postId": 123456,
    "postType": "POST",
    "reason": "我认为内容没有违规..."
  }'
```

3. 等待管理员处理（会收到通知）

---

## 十一、注意事项

1. **幂等性保证**: 生产者不再做 7 天 Redis 预检占位；每条通知带确定性 `messageId`（点赞 `like:<业务类型>:<用户>:<目标>`、审核 `content-check:<类型>:<id>:<动作>`、申诉 `appeal-handle:<id>:<pass|reject>`），重复投递由 `service-message` 消费端按 `messageId` 去重
2. **降级处理**: 审核详情中的作者信息通过 Feign 调用获取，调用失败不影响主流程
3. **同一内容一条申诉记录**: `appeal` 按 `(post_id, post_type)` 唯一，待审核期间禁止重复提交，处理完结后可再次提交（复用同一条记录、更新理由、重置状态），并非每次生成新记录
4. **评论即时发布**: 评论无待审核态，只能下架/恢复，不能审核通过/拒绝
5. **跨服务调用**: 审核与点赞结果通知在事务外用 `RabbitTemplate` 尽力直发；申诉处理结果通知经 OutboxPro 事务性发件箱与业务同事务登记，由 `service-message` 异步处理
6. **下架记录**: 所有下架操作都会在 `take_down_post` 表中留存记录，用于审计和统计

---

**文档版本**: v1.0  
**最后更新**: 2026-08-18  
**维护者**: CodeWise 后端团队

---

## 内容审核与申诉实现摘要

## 概述
本次实现为 CodeWise 平台社区模块添加了完整的**内容审核**和**申诉流程**，覆盖社区帖子（POST）、评论（COMMENT）、题解（SOLUTION）三种内容类型。

> 本节为历史开发日志（实施日期 2026-08-18），保留了当时的设计与验证记录。其中的接口路径、数据模型、枚举、通知与幂等机制等**以本文档前文「内容审核与申诉 API」「数据模型」「通知机制」章节为准**；下方片段与当前实现不一致处已在各处标注修正。

**实施日期**: 2026-08-18  
**涉及服务**: `service-community`, `service-message`, `service-api`, `service-common`

---

## 一、核心功能

### 1. 内容审核流程
- **管理员审核**: 通过 `PostCheckController` 对待审核内容进行审核
- **审核动作**: PASS（通过）、REJECT（拒绝）、TAKE_DOWN（下架）、RESTORE（恢复）
- **状态流转**: PENDING → APPROVED / REJECTED / TAKEN_DOWN
- **通知机制**: 审核结果通过 RabbitMQ 异步通知用户

### 2. 申诉流程
- **用户申诉**: 内容所有者可对被拒绝或下架（status=2/3）的内容发起申诉；同一内容一条 `appeal` 记录，待审核期间禁止重复提交，处理完结后可复用同一条记录再次提交
- **管理员处理**: 可以通过（恢复内容为 status=1）或拒绝申诉（status=1，记录 `takeDownReason`）
- **通知**: 提交申诉不通知管理员（管理员轮询待处理列表）；管理员处理结果通知申诉人，经 OutboxPro 事务性发件箱投递

### 3. 我的内容管理
- **统一查询**: 用户可按内容类型（`type=POST/COMMENT/SOLUTION`）查看自己发布的内容（该接口只按类型分列，不接收状态筛选参数，各状态一并返回）
- **状态可见**: 每条记录返回 `status`（0/1/2）与 `rejectReason`（下架/拒绝时反查 `take_down_post`）
- **申诉入口**: 被拒绝/下架的内容可直接发起申诉

---

## 二、已完成工作

### 2.1 数据库变更

#### 新增表
- ✅ `appeal` - 申诉记录表
- ✅ `take_down_post` - 下架记录表

#### 修改现有表
- ✅ `post` 表新增 `check_reason` 字段
- ✅ `comment` 表新增 `check_reason` 字段  
- ✅ `solution` 表新增 `check_reason` 字段

所有 SQL 变更已更新到 `service-community/src/main/resources/sql.sql`

### 2.2 后端开发

#### service-community
- ✅ `PostCheckController` - 审核管理接口（管理员）
- ✅ `AppealController` - 用户申诉接口
- ✅ `AppealAdminController` - 管理员申诉处理接口
- ✅ `MyContentController` - 我的内容查询接口
- ✅ `PostCheckService` - 审核业务逻辑 + 通知发送
- ✅ `AppealService` - 申诉业务逻辑 + 通知发送
- ✅ `MyContentService` - 我的内容查询逻辑
- ✅ `AppealMapper` + XML - 申诉数据访问
- ✅ `TakeDownPostMapper` + XML - 下架记录数据访问
- ✅ 实体类、VO、DTO、枚举类

#### service-message
- ✅ `CheckedHandle` - 审核通知消费者
- ✅ `AppealHandle` - 申诉通知消费者

#### service-api
- ✅ `NotificationCheckedDto` - 审核通知数据结构
- ✅ `NotificationAppealDto` - 申诉通知数据结构
- ✅ `NotificationCenterType` 新增 `CHECKED`、`APPEAL` 类型
- ✅ `WebSocketQueueName` 新增对应队列名

#### service-common
- ✅ `MqContexts` 新增审核和申诉路由键常量
- ✅ `MqConfig` 新增队列绑定配置
- ✅ `RedisContext` 新增通知幂等性 Key 前缀

### 2.3 编译验证

所有模块编译通过：
```powershell
✅ service-common - clean install
✅ service-api - clean install  
✅ service-community - clean compile
✅ service-message - clean compile
```

### 2.4 文档

- ✅ API 接口文档: 本文档“内容审核与申诉 API”章节
- ✅ 实现总结文档: 本文档“内容审核与申诉实现摘要”章节 (本文档)

---

## 三、技术架构

### 1. 模块划分

#### service-community（核心业务）
```
controller/
├── PostCheckController.java          # 审核 API（需管理员权限）
├── AppealController.java             # 用户申诉 API
├── AppealAdminController.java        # 管理员处理申诉 API
└── MyContentController.java          # 我的内容 API

service/
├── PostCheckService.java             # 审核逻辑 + 通知发送
├── AppealService.java                # 申诉逻辑 + 通知发送
└── MyContentService.java             # 我的内容查询

entry/
├── Appeal.java                       # 申诉实体（新表）
└── TakeDownPost.java                 # 下架记录实体（新表）

vo/
├── CheckDetailVo.java                # 审核详情（带作者信息）
├── AppealVo.java                     # 申诉详情
└── MyContentVo.java                  # 我的内容统一视图

enums/
└── PostStatus.java                   # 内容状态枚举
```

#### service-message（消息处理）
```
notificationcenter/handle/
├── CheckedHandle.java                # 监听审核通知队列
└── AppealHandle.java                 # 监听申诉通知队列
```

#### service-api（共享定义）
```
dto/notification/
├── NotificationCheckedDto.java       # 审核通知载荷
└── NotificationAppealDto.java        # 申诉通知载荷

enums/
├── NotificationCenterType.java       # 新增 CHECKED、APPEAL 类型
└── WebSocketQueueName.java           # 新增对应队列名
```

#### service-common（基础设施）
```
config/
├── MqContexts.java                   # 新增路由键常量
└── MqConfig.java                     # 新增队列绑定

RedisDto/
└── RedisContext.java                 # 新增幂等性 Key 前缀
```

---

### 2. 数据模型

#### Appeal（申诉表）

```java
appealId        BIGINT PK AUTO_INCREMENT   // 申诉 ID（IdType.AUTO，自增）
postId          BIGINT                      // 内容 ID（帖子/题解/评论）
postType        VARCHAR                     // POST/COMMENT/SOLUTION（枚举名）
userId          BIGINT                      // 申诉人
reason          TEXT                        // 申诉理由
takeDownReason  TEXT                        // 拒绝时管理员回复/下架原因
status          INT                         // 0-待审核 1-拒绝 2-恢复
adminUserId     BIGINT                      // 处理管理员
createTime      DATETIME
updateTime      DATETIME
```

#### TakeDownPost（下架记录表）
```java
id                BIGINT PK             // 雪花 ID（IdType.ASSIGN_ID，非自增）；VO 字段 takeDownId 序列化为字符串
rootType          VARCHAR               // POST/COMMENT/SOLUTION（枚举名）
rootId            BIGINT                // 被下架内容 ID
rootCommentId     BIGINT                // 评论所在根评论 ID
questionId        BIGINT                // 题解所属题目 ID
reason            TEXT                  // 下架原因
adminId           BIGINT                // 操作管理员
createTime        DATETIME
```

#### Post/Comment/Solution（内容状态）

内容表用整型 `status` 列表示状态（0 待审核 / 1 正常 / 2 下架，帖子另有历史值 3 审核未通过），**没有 `checkReason` 列**；审核拒绝 / 下架原因写入 `take_down_post.reason`。

---

### 3. API 设计（已按实现落地）

> 本节为早期设计稿，实际落地的接口路径与下述不同。**最终实现路径以本文档前文“内容审核与申诉 API”章节为准**，差异对照：

| 设计稿路径（已废弃） | 实际落地路径 |
| --- | --- |
| `GET /api/community/check/posts` | `GET /api/community/check/post/list` |
| `POST /api/community/check/posts/{id}` | `POST /api/community/check/post`（query 传 `postId`、`status`） |
| `POST /api/community/check/posts/{id}/takedown` / `/restore` | `POST /api/community/check/post/status` |
| `GET /api/community/appeal` / `/appeal/my` / `/appeal/{appealId}` | `POST /api/community/appeal/submit`、`GET /api/community/appeal/my`、`GET /api/community/appeal/{appealId}` |
| `GET /api/community/appeal/admin/pending` | `GET /api/community/appeal-admin/list` |
| `POST /api/community/appeal/admin/{appealId}/handle` | `POST /api/community/appeal-admin/handle` |
| `GET /api/community/my-content` | `GET /api/community/mine?type=POST&lastId=&pageSize=20` |

评论与题解的审核同理使用 `check/comment/*`、`check/solution/*` 系列路径。

---

### 4. 消息通知流程

#### 审核通知（CheckedHandle）
```
PostCheckService
  └─> RabbitMQ (NOTIFICATION_EXCHANGE → NOTIFICATION_CHECKED_ROUTING_KEY)
        └─> CheckedHandle 消费
              └─> 生成通知文案
                    └─> 写入 notification_center 表
                          └─> WebSocket 推送（如果在线）
```

**通知文案规则:**
- PASS: "您的{类型}已通过审核"
- REJECT: "您的{类型}未通过审核：{原因}"
- TAKE_DOWN: "您的{类型}因违规已被下架：{原因}"
- RESTORE: "您的{类型}已恢复显示"

#### 申诉通知（AppealHandle）

```
AppealService (用户提交)
  └─> 不发送通知（管理员通过「申诉管理列表」轮询待处理申诉）

AppealService.handleAppeal (管理员处理，@Transactional)
  └─> OutboxPro 发件箱登记 (eventType=NOTIFICATION_APPEAL, payload=NotificationDto)
        └─> Relay 经 Publisher Confirm 至少一次投递
              └─> RabbitMQ (NOTIFICATION_EXCHANGE → NOTIFICATION_APPEAL_ROUTING_KEY)
                    └─> AppealHandle 消费
                          └─> userId={申诉人}，按 messageId 幂等去重
                                └─> "您的申诉已{通过/拒绝}"
```

---

## 四、关键设计决策

### 1. 统一的通知处理
**问题**: 原代码在 `NotificationDto` 中包含 `title` 和 `message`，导致业务逻辑分散

**解决方案**:
- `NotificationDto` 不再包含 `title/message`
- 由 MQ 消费者（CheckedHandle/AppealHandle）根据 `extraData` 生成文案
- 业务层只需发送结构化数据，文案逻辑集中在 message 服务

### 2. 幂等性保证

生产者侧不再用 7 天 Redis 预检占位（旧实现占位成功但 MQ 发送失败会静默丢通知）。当前策略是**每条通知携带确定性 `messageId`，去重交给 `service-message` 消费端**：

```java
// 审核：内容类型 + 目标 ID + 动作码，避免下架后再恢复被误判为重复
String messageId = "content-check:" + type.name() + ":" + id + ":" + action.code;
// 点赞：like + 业务类型 + 点赞用户 + 目标
// 申诉：appeal-handle:<appealId>:<pass|reject>
```

审核 / 点赞通知在事务外经 `RabbitTemplate` 尽力直发；申诉处理通知改由 OutboxPro 事务性发件箱与业务同事务登记，保证不丢、至少一次投递，重复由消费端按 `messageId` 去重。

### 3. 跨服务数据查询
审核详情需要显示作者信息，通过 OpenFeign 调用 `service-user`（单个用 `getUserInfo`，列表用 `getUserList` 批量避免 N+1）：
```java
CheckDetailVo detail = CheckDetailVo.of(post);
try {
    Result<UserDto> user = userFeignClient.getUserInfo(post.getUserId());
    detail.setUserDto(user == null ? null : user.getData());
} catch (Exception e) {
    log.warn("审核详情查询作者信息失败，userId={}", post.getUserId(), e);
    detail.setUserDto(null); // 降级处理，不影响主流程
}
```

### 4. 申诉记录设计
**需求**: Q2 提到"可多次申诉即可"

**实现**: 
- 同一内容（`post_id` + `post_type`）只保留一条 `Appeal` 记录
- 待审核（`status=0`）期间重复提交被拒绝；处理完结后可再次提交，复用同一条记录、更新理由并重置为待审核
- 管理员通过「申诉管理列表」查看全部申诉记录

---

## 五、数据库变更

> 与本文档「七、数据库表结构」一致，落地字段以实体与 mapper 映射为准。

### 新增表
```sql
-- 申诉表（对应 Appeal 实体 / AppealMapper.xml）
CREATE TABLE `appeal` (
  `appeal_id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,   -- IdType.AUTO 自增
  `post_id` BIGINT NOT NULL,
  `post_type` VARCHAR(20) NOT NULL,                          -- POST/COMMENT/SOLUTION
  `user_id` BIGINT NOT NULL,
  `reason` TEXT,
  `take_down_reason` TEXT,
  `status` INT NOT NULL,                                     -- 0 待审核 / 1 拒绝 / 2 恢复
  `admin_user_id` BIGINT,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 下架记录表（对应 TakeDownPost 实体 / TakeDownPostMapper.xml）
CREATE TABLE `take_down_post` (
  `id` BIGINT NOT NULL PRIMARY KEY,                          -- IdType.ASSIGN_ID 雪花，非自增
  `root_type` VARCHAR(20) NOT NULL,
  `root_id` BIGINT NOT NULL,
  `root_comment_id` BIGINT,
  `question_id` BIGINT,
  `reason` TEXT NOT NULL,
  `admin_id` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### 内容表状态列

`post`/`comment`/`solution` 使用整型 `status` 列（0 待审核 / 1 正常 / 2 下架，帖子另有历史值 3），审核与下架原因不落在内容表，统一写入 `take_down_post.reason`；**不存在 `check_reason` 列**。

---

## 六、编译验证

已验证以下模块编译通过：
```powershell
.\mvnw.cmd -f service-common\pom.xml clean install -DskipTests  # ✅
.\mvnw.cmd -f service-api\pom.xml clean install -DskipTests     # ✅
.\mvnw.cmd -f service-community\pom.xml clean compile -DskipTests # ✅
.\mvnw.cmd -f service-message\pom.xml clean compile -DskipTests   # ✅
```

---

## 七、待完成事项

### 1. 前端开发
**需确认前端代码位置**，然后实现以下页面：

#### 管理端页面
- [ ] 审核管理页（待审核列表 + 详情 + 操作）
- [ ] 申诉处理页（待处理申诉列表 + 详情 + 通过/拒绝）
- [ ] 下架内容管理页

#### 用户端页面
- [ ] 我的内容页（查看所有内容状态）
- [ ] 申诉页面（提交申诉 + 查看申诉历史）
- [ ] 通知中心（接收审核/申诉结果）

### 2. 权限配置
确认以下配置已添加：
- [-] `service-gateway` 放行审核相关路径（整服务路由 `Path=/api/community/**`，无需逐路径配置）
- [-] `@RequireAdmin` 注解生效（由 `service-common` 的 `AdminAuthAspect` 实现 api-governance `PreFilter`，经 `META-INF/spring/...AutoConfiguration.imports` 自动注册；2026-09-11 修复前该切面从未注册，详见 `docs/maintenance/repair-plan.md`）

### 3. 测试
- [ ] 单元测试（Service 层）
- [ ] 集成测试（完整审核流程）
- [ ] MQ 消息消费测试
- [ ] 幂等性测试

---

## 七、待完成事项

### 7.1 前端开发
**需确认前端代码位置**，然后实现以下页面：

#### 管理端页面
- [ ] 审核管理页（待审核列表 + 详情 + 操作）
- [ ] 申诉处理页（待处理申诉列表 + 详情 + 通过/拒绝）
- [ ] 下架内容管理页（查看历史下架记录）

#### 用户端页面
- [ ] 我的内容页（查看所有内容状态）
- [ ] 申诉页面（提交申诉 + 查看申诉历史）
- [ ] 通知中心（接收审核/申诉结果通知）

### 7.2 权限配置
确认以下配置已添加：
- [-] `service-gateway` 放行审核相关路径（整服务路由 `Path=/api/community/**`，无需逐路径配置）
- [-] `@RequireAdmin` 注解生效（由 `service-common` 的 `AdminAuthAspect` 实现 api-governance `PreFilter`，经 `META-INF/spring/...AutoConfiguration.imports` 自动注册；2026-09-11 修复前该切面从未注册，详见 `docs/maintenance/repair-plan.md`）

### 7.3 测试
- [ ] 单元测试（Service 层）
- [ ] 集成测试（完整审核流程 + 申诉流程）
- [ ] MQ 消息消费测试
- [ ] 幂等性测试（Redis Key 过期验证）
- [ ] 跨服务调用测试（Feign 降级处理）

### 7.4 部署前检查
- [ ] RabbitMQ 队列自动创建验证
- [ ] Redis 连接配置
- [ ] 数据库迁移脚本执行
- [ ] 环境变量配置（JWT_SECRET, CODEWISE_INTERNAL_TOKEN）

---

## 八、文件清单

### 新增文件（Untracked）
```
service-api/src/main/java/org/example/serviceapi/dto/notification/
├── NotificationAppealDto.java
└── NotificationCheckedDto.java

service-community/src/main/java/org/example/servicecommunity/
├── controller/
│   ├── PostCheckController.java
│   ├── AppealController.java
│   ├── AppealAdminController.java
│   └── MyContentController.java
├── service/
│   ├── PostCheckService.java
│   ├── AppealService.java
│   └── MyContentService.java
├── vo/
│   ├── CheckDetailVo.java
│   ├── AppealVo.java
│   └── MyContentVo.java
├── enums/
│   └── PostStatus.java
├── entry/
│   ├── Appeal.java
│   └── TakeDownPost.java
├── mapper/
│   └── AppealMapper.java
└── Dto/
    └── AppealDto.java

service-message/src/main/java/org/example/servicemessage/notificationcenter/handle/
├── AppealHandle.java
└── CheckedHandle.java
```

### 修改文件（Modified）
```
service-api/src/main/java/org/example/serviceapi/enums/
├── NotificationCenterType.java       # 新增 CHECKED、APPEAL
└── WebSocketQueueName.java           # 新增队列名

service-common/src/main/java/org/example/servicecommon/
├── RedisDto/RedisContext.java        # 新增幂等性Key
├── config/MqContexts.java            # 新增路由键
└── config/MqConfig.java              # 新增队列绑定
```

---

## 九、注意事项

1. **不要提交 application.yaml 的敏感配置**（已在 .gitignore 中）
2. **RabbitMQ 队列会自动创建**（通过 MqConfig 声明式配置）
3. **Redis Key 前缀已统一**（RedisContext 中管理）
4. **雪花ID 生成器已配置**（依赖 service-common）
5. **跨服务调用有熔断降级**（Feign 调用失败不影响主流程）
6. **幂等性保证**: 通知带确定性 `messageId`，由 `service-message` 消费端去重；申诉处理通知经 OutboxPro 事务性投递，生产者侧不再做 7 天 Redis 预检
7. **评论即时发布**: 评论无待审核态，只能下架/恢复
8. **申诉**: 同一内容一条 `appeal` 记录，待审核期间禁止重复提交，处理完结后可复用记录再次申诉

---

## 十、后续优化建议

1. **审核规则引擎**: 目前是人工审核，可接入敏感词过滤/AI 自动审核
2. **审核日志**: 记录每次审核操作的管理员和时间戳（审计追踪）
3. **批量审核**: 支持管理员一次审核多条内容
4. **申诉超时自动关闭**: 例如 7 天未处理的申诉自动关闭
5. **审核统计**: 管理员审核效率统计、申诉通过率等数据看板
6. **内容评分系统**: 基于审核历史对用户/内容进行信用评分
7. **自动下架规则**: 多次违规自动下架，减少人工工作量

---

## 十一、API 快速参考

### 管理员审核接口
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/community/check/post/list` | GET | 待审核帖子列表 |
| `/api/community/check/post` | POST | 审核帖子（通过/拒绝） |
| `/api/community/check/post/status` | POST | 下架/恢复帖子 |
| `/api/community/check/comment/list` | GET | 待审核评论列表 |
| `/api/community/check/comment/status` | POST | 下架/恢复评论 |
| `/api/community/check/solution/list` | GET | 待审核题解列表 |
| `/api/community/check/solution` | POST | 审核题解（通过/拒绝） |
| `/api/community/check/solution/status` | POST | 下架/恢复题解 |
| `/api/community/check/takedown/list` | GET | 下架记录列表 |

### 申诉管理接口
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/community/appeal-admin/list` | GET | 申诉列表（可按状态筛选） |
| `/api/community/appeal-admin/handle` | POST | 处理申诉（通过/拒绝） |

### 用户接口
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/community/appeal/submit` | POST | 提交申诉 |
| `/api/community/mine` | GET | 查看我的内容 |

详细 API 见本文档“内容审核与申诉 API”章节。

---

## 十二、相关文档

- **API 接口文档**：本文档“内容审核与申诉 API”章节
- **项目架构文档**: `docs/technical-design.md`
- **社区模块设计**: `docs/project-structure.md`
- **后端 API 总览**: `docs/backend-controller-api.md`

---

**实施状态**: ✅ 后端核心功能已完成并编译通过  
**后续事项**: 前端联调、集成测试与生产化建设（见 `docs/maintenance/repair-plan.md`）  

**最后更新**: 2026-08-29  
**维护者**: CodeWise 后端团队
