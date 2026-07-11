# 社区模块接口文档

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

返回：`Result<PostVo>`，包括正文、当前用户是否点赞，以及按标签分组的相关帖子。

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

热点分数综合帖子点赞数、评论数和发布时间计算。Redis 使用 ZSet 保存排名，每 5 分钟根据数据库中的计数和时间衰减重新计算；点赞和评论操作会在重算间隔内实时调整分数。

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

只有帖子发布者可以删除。帖子主体删除后，相关标签、评论、帖子点赞和评论点赞记录异步级联清理，同时从热点榜和帖子缓存中移除。

### 删除评论

```http
DELETE /api/community/comments/{commentId}
Authorization: Bearer <token>
```

只有评论发布者可以删除。删除根评论时会异步删除该评论下的回复以及对应点赞记录，并同步调整帖子评论数和热点分数。

异步删除状态会短暂保存在 Redis 中。接口返回成功表示删除任务已经受理；若异步级联 SQL 失败，服务会记录错误并将任务状态标记为 `failed`。

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
