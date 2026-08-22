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
| `postId` | `Long` | 是 | 帖子 ID |
| `rootCommentId` | `Long` | 否 | 默认 `-1` 不按根评论筛选；传根评论 ID 查询其回复 |
| `lastId` | `Long` | 否 | 下一页游标 |
| `pageSize` | `Integer` | 否 | 默认 20，范围 1 到 100 |

返回：`Result<CursorPageResult<CommentVo>>`，每条记录包含当前用户的 `isLike` 状态。

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
- 通知发送到 `notification.exchange`，由 `service-message` 持久化并尝试 WebSocket 推送。
- 点赞计数仍通过 Redis 增量桶定时批量回写 MySQL。

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

点赞记录会先写入 Redis 增量桶，再由定时任务批量更新帖子或评论的 `likeCount`，因此计数展示存在短暂延迟。

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
| `userId` | `Long` | 发布用户 ID |
| `userName` | `String` | 发布用户名 |
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
| `relatedPost` | `Map<String, Map<Long, String>>` | 标签到相关帖子 ID、标题的映射 |

### CommentVo

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `commentId` | `Long` | 评论 ID |
| `comment` | `String` | 评论内容 |
| `userId` | `Long` | 评论用户 ID |
| `userName` | `String` | 评论用户名 |
| `postId` | `Long` | 所属帖子 ID |
| `rootCommentId` | `Long` | 所属根评论 ID |
| `replyUserId` | `Long` | 被回复用户 ID |
| `replyUserName` | `String` | 被回复用户名 |
| `likeCount` | `Long` | 点赞数 |
| `isLike` | `Boolean` | 当前用户是否已点赞 |
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

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "postId": 123456,
        "postTitle": "如何优化算法性能",
        "postContent": "我在做题时...",
        "userId": 1001,
        "userName": "张三",
        "status": 0,
        "createTime": "2026-08-18T10:30:00"
      }
    ],
    "hasMore": true,
    "total": 25
  }
}
```

### 1.2 获取帖子审核详情

```http
GET /api/community/check/post/detail?postId=123456
```

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "content": {
      "postId": 123456,
      "postTitle": "标题",
      "postContent": "正文...",
      "status": 0,
      "createTime": "2026-08-18T10:30:00"
    },
    "author": {
      "userId": 1001,
      "userName": "张三",
      "avatar": "https://..."
    }
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
| status | Integer | 是 | 1=通过，2=拒绝 |

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

### 1.5 获取待审核评论列表

```http
GET /api/community/check/comment/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标 |
| pageSize | Integer | 否 | 每页数量，默认 10 |
| rootCommentId | Long | 否 | 筛选某个一级评论下的回复 |
| type | PostType | 否 | POST 或 SOLUTION |

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "commentId": 789,
        "comment": "评论内容...",
        "userId": 1002,
        "userName": "李四",
        "postId": 123456,
        "rootCommentId": null,
        "status": 0,
        "type": "POST",
        "createTime": "2026-08-18T11:00:00"
      }
    ],
    "hasMore": false,
    "total": 8
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

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "solutionId": 5001,
        "questionId": 101,
        "solutionTitle": "双指针解法",
        "solutionContent": "这道题可以用...",
        "solutionUserId": 1003,
        "status": 0,
        "createTime": "2026-08-18T12:00:00"
      }
    ],
    "hasMore": true,
    "total": 15
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
| solutionId | Long | 是 | 题解 ID |
| status | Integer | 是 | 1=通过，2=拒绝 |

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
| lastId | Long | 否 | 游标 |
| pageSize | Integer | 否 | 每页数量，默认 10 |

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "id": 9001,
        "rootType": "POST",
        "rootId": 123456,
        "rootCommentId": null,
        "questionId": null,
        "reason": "涉及敏感内容",
        "adminId": 1,
        "createTime": "2026-08-18T14:00:00"
      }
    ],
    "hasMore": false,
    "total": 3
  }
}
```

---

## 二、申诉管理 API（管理员专用）

**权限要求**: `@RequireAdmin`  
**基础路径**: `/api/community/appeal-admin`（也可通过 `/api/community/check/appeal` 访问）

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

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "appealId": 2001,
        "userId": 1001,
        "rootType": "POST",
        "rootId": 123456,
        "rootCommentId": null,
        "questionId": null,
        "reason": "我认为内容没有违规...",
        "status": 0,
        "adminReason": null,
        "handlerId": null,
        "handledAt": null,
        "createTime": "2026-08-18T15:00:00"
      }
    ],
    "hasMore": true,
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
- `pass=true`: 申诉状态改为 `APPROVED(2)`，原内容状态恢复为 `APPROVED(1)`
- `pass=false`: 申诉状态改为 `REJECTED(1)`，原内容状态保持不变

---

## 三、用户申诉 API

**基础路径**: `/api/community/appeal`

### 3.1 提交申诉

```http
POST /api/community/appeal/submit
```

**请求体**:
```json
{
  "rootType": "POST",
  "rootId": 123456,
  "rootCommentId": null,
  "questionId": null,
  "reason": "我认为我的帖子没有违规，请重新审核..."
}
```

**字段说明**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| rootType | String | 是 | POST/COMMENT/SOLUTION |
| rootId | Long | 是 | 内容 ID（帖子/评论/题解的 ID） |
| rootCommentId | Long | 否 | 如果是评论，填写 comment_id |
| questionId | Long | 否 | 关联题目 ID（题解时使用） |
| reason | String | 是 | 申诉理由 |

**响应**:
```json
{
  "code": 200,
  "data": null
}
```

**说明**: 
- 同一内容可无限次申诉，每次生成新的 `Appeal` 记录
- 申诉成功后，系统会通知管理员

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

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "id": 123456,
        "type": "POST",
        "title": "如何优化算法性能",
        "content": "我在做题时...",
        "status": 1,
        "checkReason": null,
        "createTime": "2026-08-18T10:30:00"
      },
      {
        "id": 123457,
        "type": "POST",
        "title": "另一个帖子",
        "content": "...",
        "status": 2,
        "checkReason": "内容涉及违规",
        "createTime": "2026-08-17T09:00:00"
      }
    ],
    "hasMore": true,
    "total": 45
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

### 5.1 内容状态枚举 (PostStatus)

```java
public enum PostStatus {
    PENDING(0, "待审核"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已拒绝/已下架");
}
```

### 5.2 内容类型枚举 (PostType)

```java
public enum PostType {
    POST,       // 社区帖子
    COMMENT,    // 评论
    SOLUTION    // 题解
}
```

### 5.3 申诉状态枚举 (AppealStatus)

```java
public enum AppealStatus {
    PENDING(0, "待处理"),
    REJECTED(1, "已拒绝"),
    APPROVED(2, "已通过/已恢复")
}
```

---

## 六、通知机制

### 6.1 审核通知

**触发场景**: 管理员审核内容后（通过/拒绝/下架/恢复）

**通知对象**: 内容所有者

**通知内容**:
- PASS: "您的{类型}已通过审核"
- REJECT: "您的{类型}未通过审核：{原因}"
- TAKE_DOWN: "您的{类型}因违规已被下架：{原因}"
- RESTORE: "您的{类型}已恢复显示"

**通知类型**: `NotificationCenterType.CHECKED`

**消息队列**: `NOTIFICATION_CHECKED_ROUTING_KEY`

### 6.2 申诉通知

#### 用户提交申诉 → 通知管理员

**通知对象**: 管理员（userId=0）

**通知内容**: "用户{userName}提交了申诉"

#### 管理员处理申诉 → 通知用户

**通知对象**: 申诉人

**通知内容**:
- 通过: "您的申诉已通过，内容已恢复"
- 拒绝: "您的申诉已被拒绝：{管理员回复}"

**通知类型**: `NotificationCenterType.APPEAL`

**消息队列**: `NOTIFICATION_APPEAL_ROUTING_KEY`

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
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
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

### 7.3 post/comment/solution 表新增字段

```sql
-- Post 表
ALTER TABLE `post` 
  ADD COLUMN `check_reason` TEXT COMMENT '审核/下架原因';

-- Comment 表
ALTER TABLE `comment` 
  ADD COLUMN `check_reason` TEXT COMMENT '审核/下架原因';

-- Solution 表
ALTER TABLE `solution` 
  ADD COLUMN `check_reason` TEXT COMMENT '审核/下架原因';
```

---

## 八、权限要求

### 8.1 管理员权限

以下接口需要 `@RequireAdmin` 权限（通过 JWT 中的 `role` 字段判断）：

- 所有 `/api/community/check/**` 路径
- 所有 `/api/community/appeal-admin/**` 路径

### 8.2 用户权限

以下接口需要普通登录用户权限（JWT 验证）：

- `/api/community/appeal/submit` - 提交申诉
- `/api/community/mine` - 查看我的内容

**身份来源**: 从 `UserContext.getUserId()` 获取，不接受客户端传递的 userId 参数

---

## 九、错误码

| code | 说明 |
|------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未登录或 Token 无效 |
| 403 | 无权限（非管理员） |
| 404 | 内容不存在 |
| 500 | 服务器内部错误 |

**错误响应示例**:
```json
{
  "code": 403,
  "message": "需要管理员权限",
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
    "rootType": "POST",
    "rootId": 123456,
    "reason": "我认为内容没有违规..."
  }'
```

3. 等待管理员处理（会收到通知）

---

## 十一、注意事项

1. **幂等性保证**: 所有通知消息使用 Redis 实现 7 天幂等，相同操作不会重复发送通知
2. **降级处理**: 审核详情中的作者信息通过 Feign 调用获取，调用失败不影响主流程
3. **无限次申诉**: 用户可对同一内容多次申诉，每次生成新记录
4. **评论即时发布**: 评论无待审核态，只能下架/恢复，不能审核通过/拒绝
5. **跨服务调用**: 审核相关操作会触发 RabbitMQ 消息，由 `service-message` 异步处理通知
6. **下架记录**: 所有下架操作都会在 `take_down_post` 表中留存记录，用于审计和统计

---

**文档版本**: v1.0  
**最后更新**: 2026-08-18  
**维护者**: CodeWise 后端团队

---

## 内容审核与申诉实现摘要

## 概述
本次实现为 CodeWise 平台社区模块添加了完整的**内容审核**和**申诉流程**，覆盖社区帖子（POST）、评论（COMMENT）、题解（SOLUTION）三种内容类型。

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
- **用户申诉**: 内容所有者可对被拒绝或下架的内容发起申诉（无限次）
- **管理员处理**: 可以通过（恢复内容）或拒绝申诉
- **双向通知**: 
  - 用户提交申诉 → 通知管理员
  - 管理员处理申诉 → 通知用户

### 3. 我的内容管理
- **统一查询**: 用户可查看自己的所有内容（帖子/评论/题解）
- **状态筛选**: 支持按状态（待审核/已通过/已拒绝/已下架）筛选
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
appealId          BIGINT PRIMARY KEY    // 雪花ID
userId            BIGINT                // 申诉人
rootType          ENUM                  // POST/COMMENT/SOLUTION
rootId            BIGINT                // 内容ID
rootCommentId     BIGINT                // 评论ID（仅COMMENT类型）
questionId        BIGINT                // 关联题目ID（题解/评论）
reason            TEXT                  // 申诉理由
status            ENUM                  // PENDING/APPROVED/REJECTED
adminReason       TEXT                  // 管理员回复
handlerId         BIGINT                // 处理人ID
handledAt         DATETIME              // 处理时间
createTime        DATETIME
```

#### TakeDownPost（下架记录表）
```java
id                BIGINT PRIMARY KEY
rootType          ENUM
rootId            BIGINT
rootCommentId     BIGINT
questionId        BIGINT
reason            TEXT                  // 下架原因
adminId           BIGINT                // 操作管理员
createTime        DATETIME
```

#### Post/Comment/Solution（内容表修改）
新增字段：
- `status` ENUM: PENDING/APPROVED/REJECTED/TAKEN_DOWN
- `checkReason` TEXT: 审核不通过原因

---

### 3. API 设计

#### 审核 API（管理员专用）
```http
# 获取待审核列表
GET /api/community/check/posts?cursor=&size=20
GET /api/community/check/comments?cursor=&size=20
GET /api/community/check/solutions?cursor=&size=20

# 获取审核详情（带作者信息）
GET /api/community/check/detail?type=POST&id=123

# 执行审核
POST /api/community/check/posts/{id}
Body: { "action": "PASS/REJECT", "reason": "..." }

POST /api/community/check/comments/{id}
POST /api/community/check/solutions/{id}

# 下架/恢复内容
POST /api/community/check/posts/{id}/takedown
Body: { "reason": "违规原因" }

POST /api/community/check/posts/{id}/restore
```

#### 申诉 API（用户）
```http
# 提交申诉
POST /api/community/appeal
Body: {
  "rootType": "POST",
  "rootId": 123,
  "rootCommentId": null,
  "questionId": null,
  "reason": "我认为..."
}

# 查看我的申诉历史
GET /api/community/appeal/my?rootType=POST&rootId=123&cursor=&size=20

# 查看申诉详情
GET /api/community/appeal/{appealId}
```

#### 申诉管理 API（管理员）
```http
# 获取待处理申诉列表
GET /api/community/appeal/admin/pending?cursor=&size=20

# 处理申诉
POST /api/community/appeal/admin/{appealId}/handle
Body: {
  "pass": true,           // true=通过(恢复内容), false=拒绝
  "adminReason": "..."    // 处理说明
}
```

#### 我的内容 API（用户）
```http
# 查看我的所有内容
GET /api/community/my-content?status=PENDING&cursor=&size=20
# status可选: PENDING/APPROVED/REJECTED/TAKEN_DOWN
```

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
  └─> RabbitMQ (NOTIFICATION_EXCHANGE → NOTIFICATION_APPEAL_ROUTING_KEY)
        └─> AppealHandle 消费
              └─> userId=0 表示管理员通知
                    └─> "用户提交了申诉"

AppealService (管理员处理)
  └─> RabbitMQ
        └─> AppealHandle 消费
              └─> userId={申诉人} 表示用户通知
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
```java
// 每次操作生成唯一 messageId
String messageId = "check:" + postId + ":" + action;
String redisKey = RedisContext.NOTIFICATION_IDEMPOTENT_KEY + messageId;

// 7天内重复操作不会重复发送通知
Boolean set = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", 7, TimeUnit.DAYS);
if (Boolean.TRUE.equals(set)) {
    rabbitTemplate.convertAndSend(...);
}
```

### 3. 跨服务数据查询
审核详情需要显示作者信息，通过 OpenFeign 调用 `service-user`:
```java
CheckDetailVo detail = new CheckDetailVo();
detail.setContent(post.getContent());

try {
    Result<User> userResult = userFeignClient.getUserById(post.getUserId());
    detail.setAuthor(userResult.getData());
} catch (Exception e) {
    log.warn("获取用户信息失败: userId={}", post.getUserId(), e);
    detail.setAuthor(null); // 降级处理，不影响主流程
}
```

### 4. 申诉无限次设计
**需求**: Q2 提到"无限次申诉即可"

**实现**: 
- 不限制同一内容的申诉次数
- 每次提交生成新的 `Appeal` 记录
- 管理员可查看完整申诉历史

---

## 五、数据库变更（需执行）

### 新增表
```sql
-- 申诉表
CREATE TABLE `appeal` (
  `appeal_id` BIGINT PRIMARY KEY,
  `user_id` BIGINT NOT NULL,
  `root_type` ENUM('POST', 'COMMENT', 'SOLUTION') NOT NULL,
  `root_id` BIGINT NOT NULL,
  `root_comment_id` BIGINT,
  `question_id` BIGINT,
  `reason` TEXT NOT NULL,
  `status` ENUM('PENDING', 'APPROVED', 'REJECTED') DEFAULT 'PENDING',
  `admin_reason` TEXT,
  `handler_id` BIGINT,
  `handled_at` DATETIME,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_user` (`user_id`),
  INDEX `idx_root` (`root_type`, `root_id`),
  INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 下架记录表
CREATE TABLE `take_down_post` (
  `id` BIGINT PRIMARY KEY,
  `root_type` ENUM('POST', 'COMMENT', 'SOLUTION') NOT NULL,
  `root_id` BIGINT NOT NULL,
  `root_comment_id` BIGINT,
  `question_id` BIGINT,
  `reason` TEXT NOT NULL,
  `admin_id` BIGINT NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_root` (`root_type`, `root_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 修改现有表
```sql
-- Post 表
ALTER TABLE `post` 
  ADD COLUMN `status` ENUM('PENDING', 'APPROVED', 'REJECTED', 'TAKEN_DOWN') DEFAULT 'PENDING',
  ADD COLUMN `check_reason` TEXT;

-- Comment 表
ALTER TABLE `comment` 
  ADD COLUMN `status` ENUM('PENDING', 'APPROVED', 'REJECTED', 'TAKEN_DOWN') DEFAULT 'PENDING',
  ADD COLUMN `check_reason` TEXT;

-- Solution 表
ALTER TABLE `solution` 
  ADD COLUMN `status` ENUM('PENDING', 'APPROVED', 'REJECTED', 'TAKEN_DOWN') DEFAULT 'PENDING',
  ADD COLUMN `check_reason` TEXT;
```

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
- [ ] `service-gateway` 放行审核相关路径
- [ ] `@RequireAdmin` 注解生效（拦截器配置）

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
- [ ] `service-gateway` 放行审核相关路径
- [ ] `@RequireAdmin` 注解生效（拦截器配置）

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
6. **幂等性保证**: 所有通知使用 Redis 实现 7 天幂等，避免重复发送
7. **评论即时发布**: 评论无待审核态，只能下架/恢复
8. **无限次申诉**: 用户可对同一内容多次申诉

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
**待办事项**: 前端开发、权限配置、集成测试  
**会话交接**: 后端实现已完成，前端开发待确认代码位置后继续

**最后更新**: 2026-08-18  
**维护者**: CodeWise 后端团队
