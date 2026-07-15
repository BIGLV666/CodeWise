# CodeWise 后端 Controller 接口文档

本文档按当前源码中的 `@RestController` / HTTP Mapping 整理，覆盖：

- `service-user`
- `service-question`
- `service-review`
- `service-community`
- 服务间内部 HTTP 接口

## 调用约定

### 网关与直连地址

| 服务 | 端口 | 网关路由 | 说明 |
| --- | --- | --- | --- |
| `service-gateway` | `8082` | - | 推荐前端统一访问网关 |
| `service-user` | `8081` | `/api/user/**` | 用户、登录、头像接口 |
| `service-question` | `8084` | `/api/question/**` | 题目、测试点、提交、调试接口 |
| `service-review` | `8085` | 当前未配置 | 收藏夹/复习服务，当前需直连或补 gateway 路由 |
| `service-community` | `8087` | 当前未配置 | 帖子、评论和点赞，当前需直连或补 gateway 路由 |

通过网关调用示例：

```http
GET http://localhost:8082/api/question/cursorquestions?pageSize=20
Authorization: Bearer <token>
```

直连服务调用示例：

```http
GET http://localhost:8085/api/review/favorites/list
Authorization: Bearer <token>
```

### 鉴权说明

网关 `AuthGlobalFilter` 当前规则：

- 路径包含 `login` 或 `register` 的请求放行。
- WebSocket 请求可通过 query 参数 `token` 或 `Authorization: Bearer <token>` 鉴权。
- 其他请求必须带：

```http
Authorization: Bearer <token>
```

网关验证后会向下游追加：

```http
X-User-Id: <userId>
X-Internal-Token: codewise-secret-2026
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
## service-user 用户模块

基础路径：`/api/user`

### 健康测试

```http
GET /api/user/hello
```

说明：简单连通性测试，直接返回字符串 `"hello"`，不包 `Result`。

### 邮箱预注册

```http
POST /api/user/emailregister
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `email` | `String` | 是 | 注册邮箱 |
| `password` | `String` | 是 | 初始密码 |
| `username` | `String` | 是 | 用户名 |

用法说明：

- 该接口会生成验证码并发送到邮箱。
- 注册信息临时写入 Redis，有效期约 5 分钟。
- 下一步调用 `/api/user/register` 完成激活。

返回：`Result<String>`，成功 `data` 为 `"success"`。

### 激活注册

```http
POST /api/user/register
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `number` | `String` | 是 | 邮箱或手机号；邮箱注册时传邮箱 |
| `code` | `String` | 是 | 验证码 |

用法说明：

- 必须先调用 `/emailregister`。
- 验证码正确后真正插入用户表。

返回：`Result<String>`。

### 登录

```http
POST /api/user/login
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | `String` | 是 | 用户名 |
| `password` | `String` | 是 | 密码 |

返回：`Result<Map<String,Object>>`

`data` 结构：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `token` | `String` | JWT，后续请求放入 `Authorization: Bearer <token>` |
| `user` | `UserDto` | 当前用户信息 |

示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "jwt-token",
    "user": {
      "userId": 1,
      "userName": "alice"
    }
  }
}
```

### 修改当前用户密码

```http
POST /api/user/updatepassword
Authorization: Bearer <token>
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `oldPassword` | `String` | 是 | 旧密码 |
| `newPassword` | `String` | 是 | 新密码 |

用法说明：根据当前登录用户修改密码，旧密码必须正确。

返回：`Result<String>`。

### 找回密码-发送验证码

```http
POST /api/user/updatepasswordforemail
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `email` | `String` | 是 | 用户邮箱 |
| `password` | `String` | 是 | 新密码 |

用法说明：

- 会发送验证码到邮箱。
- 新密码先暂存到 Redis。
- 下一步调用 `/api/user/updatefromcode` 确认修改。

返回：`Result<String>`。

### 找回密码-验证码确认

```http
POST /api/user/updatefromcode
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `number` | `String` | 是 | 邮箱或手机号 |
| `code` | `String` | 是 | 验证码 |

返回：`Result<String>`。

### 根据用户 ID 查询用户

```http
GET /api/user/getuserbyid?id=1
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | `Long` | 是 | 用户 ID |

返回：`Result<UserDto>`。

### 修改当前用户昵称

```http
PUT /api/user/nickname?nickName=CodeWise
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `nickName` | `String` | 是 | 新昵称，不能为空，最长 30 个字符 |

返回：`Result<UserDto>`，`data` 为更新后的当前用户信息。

### 修改当前用户个人简介

```http
PUT /api/user/bio?bio=热爱算法
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `bio` | `String` | 是 | 个人简介，最长 500 个字符 |

返回：`Result<UserDto>`，`data` 为更新后的当前用户信息。

### 修改当前用户生日

```http
PUT /api/user/birthday?birthday=2000-01-01
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `birthday` | `String` | 是 | 生日，格式 `yyyy-MM-dd`，不能晚于当前日期 |

返回：`Result<UserDto>`，`data` 为更新后的当前用户信息。

### 上传/更新头像

```http
POST /api/user/avatar
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

表单字段：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | `MultipartFile` | 是 | 图片文件 |

用法说明：

- 仅允许真实图片文件。
- 文件扩展名仅允许 `jpg`、`jpeg`、`png`、`gif`。
- 文件大小不能超过 5MB。
- 上传成功后更新当前用户头像，并尝试删除旧头像。
- 如果头像数据库更新失败，会自动删除本次新上传的文件，避免产生垃圾文件。

返回：`Result<String>`。

## service-question 题目模块

基础路径：`/api/question`

### HTML 导入题目

```http
POST /api/question/html
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

表单字段：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | `MultipartFile` | 是 | 洛谷等题目 HTML 文件 |

用法说明：

- 服务读取 HTML 内容并调用解析逻辑生成 `Question`。
- 适合后台导入题目，不适合普通用户频繁调用。

返回：`Result<Question>`。

### 新增题目

```http
POST /api/question/addquestion
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`InsertQuestionDto`

```json
{
  "title": "A+B Problem",
  "description": "题目描述",
  "inputDesc": "输入说明",
  "outputDesc": "输出说明",
  "sampleInput": "1 2",
  "sampleOutput": "3",
  "hint": "",
  "tags": "入门",
  "timeLimit": 1000,
  "memoryLimit": 128
}
```

返回：`Result<Question>`。

### 查询题目详情

```http
GET /api/question/getquestionbyid?questionId=1
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `questionId` | `Long` | 是 | 题目 ID |

返回：`Result<Question>`。

### 更新题目

```http
PUT /api/question/updatequestion?questionId=1
Authorization: Bearer <token>
Content-Type: application/json
```

Query 参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `questionId` | `Long` | 是 | 题目 ID |

请求体：`InsertQuestionDto`，字段同新增题目。

返回：`Result<Question>`。

### 删除题目

```http
DELETE /api/question/deletequestion?questionId=1
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `questionId` | `Long` | 是 | 题目 ID |

返回：`Result<Void>`，成功消息为“删除成功”。

### 游标分页查询题目

```http
GET /api/question/cursorquestions?pageSize=20&lastId=100&difficulty=2&status=1&title=dp
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `pageSize` | `Integer` | 是 | 每页数量 |
| `lastId` | `Long` | 否 | 上一页最后一条记录 ID，第一页不传 |
| `difficulty` | `Integer` | 否 | 难度过滤 |
| `status` | `Integer` | 否 | 状态过滤 |
| `title` | `String` | 否 | 标题关键字 |

返回：`Result<CursorPageResult<ReturnQuestionDto>>`

`CursorPageResult` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `records` | `List<T>` | 当前页数据 |
| `nextCursor` | `Long` | 下一页游标 |
| `hasNext` | `Boolean` | 是否还有下一页 |

`records` 中每条题目为 `ReturnQuestionDto`，用于题目列表页：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `questionId` | `Long` | 题目 ID |
| `questionTitle` | `String` | 题目标题 |
| `difficulty` | `Integer` | 难度 |
| `tags` | `String` | 标签 |
| `totalSubmit` | `Long` | 总提交数 |
| `totalAc` | `Long` | AC 数 |
| `passRate` | `BigDecimal` | 通过率 |
| `status` | `Integer` | 当前用户做题状态：`0` 未尝试，`1` 已通过，`2` 已尝试但未通过 |

用法说明：

- 第一页不传 `lastId`。
- 后续页传上一页返回的 `nextCursor`。
- 该接口不再返回总数，使用 `hasNext` 判断是否继续加载。
- 如果请求带有有效登录态，后端会根据 `submit_record` 聚合当前用户在本页题目上的提交状态；未登录或无提交记录时状态为 `0`。
- 状态聚合优先级为：只要该用户该题存在任意一次 `AC`，则返回 `1`；否则只要提交过，返回 `2`。

### WebSocket 广播测试

```http
GET /api/question/test-ws
Authorization: Bearer <token>
```

说明：

- 向 `/topic/judge-result` 广播测试消息。
- 主要用于联调 WebSocket，不是核心业务接口。

返回：普通字符串。

## service-question 判题与提交记录

### 提交判题

```http
POST /api/question/judge
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`GetCodeDto`

```json
{
  "code": "#include <iostream>\nint main(){return 0;}",
  "language": "cpp",
  "questionId": 1
}
```

字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | `String` | 是 | 提交代码 |
| `language` | `String` | 是 | 语言标识 |
| `questionId` | `Long` | 是 | 题目 ID |

返回：`Result<Long>`，`data` 为 `submitRecordId`。

用法说明：

- 接口只返回提交记录 ID。
- 判题结果异步处理，前端可通过提交记录接口或 WebSocket 获取后续状态。

### 自定义调试

```http
POST /api/question/debug
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`DebugDto`

```json
{
  "userId": 1,
  "code": "print(input())",
  "language": "python",
  "questionId": 1,
  "tests": [
    {
      "input": "hello",
      "output": "hello"
    }
  ]
}
```

说明：

- `tests` 为临时调试用例，不一定写入正式测试点。
- 当前 `userId` 字段存在于 DTO 中，但正常应以登录态为准。

返回：`Result<String>`。

### 根据提交记录 ID 查询

```http
GET /api/question/getsubmitrecordbyid?submitRecordId=1
Authorization: Bearer <token>
```

返回：`Result<SubmitRecord>`。

### 查询某题提交记录

```http
GET /api/question/getsubmitrecordsbyquestionid?questionId=1
Authorization: Bearer <token>
```

返回：`Result<List<SubmitRecord>>`。

### 查询当前用户提交记录

```http
GET /api/question/getsubmitrecordsbyuserid
Authorization: Bearer <token>
```

说明：不需要传 `userId`，服务从登录态读取当前用户。

返回：`Result<List<SubmitRecord>>`。

### 删除提交记录

```http
DELETE /api/question/deletesubmitrecord?submitRecordId=1
Authorization: Bearer <token>
```

返回：`Result<Void>`。

## service-question 测试点管理

### 查询每题测试点数量

```http
GET /api/question/getallquestion
Authorization: Bearer <token>
```

说明：返回 `List<Map<String,Object>>`，用于统计每个题目的测试点数量。

返回：`Result<List<Map<String,Object>>>`。

### 新增测试点

```http
POST /api/question/addtestcase
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`TestCase`

```json
{
  "questionId": 1,
  "inputData": "1 2",
  "expectedOutput": "3",
  "isSample": 1,
  "isHidden": 0,
  "sortOrder": 1,
  "scoreWeight": 10,
  "timeLimit": 1000,
  "memoryLimit": 128
}
```

返回：`Result<TestCase>`。

### 根据测试点 ID 查询

```http
GET /api/question/gettestcasebyid?caseId=1
Authorization: Bearer <token>
```

返回：`Result<TestCase>`。

### 查询某题所有测试点

```http
GET /api/question/gettestcasesbyquestionid?questionId=1
Authorization: Bearer <token>
```

返回：`Result<List<TestCase>>`。

### 更新测试点

```http
PUT /api/question/updatetestcase?caseId=1
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`InsertTestCaseDto`

```json
{
  "inputData": "1 2",
  "expectedOutput": "3",
  "isSample": 1,
  "isHidden": 0,
  "sortOrder": 1,
  "scoreWeight": 10,
  "timeLimit": 1000,
  "memoryLimit": 128
}
```

说明：DTO 字段为空时由 Service 决定是否保留原值。

返回：`Result<TestCase>`。

### 删除测试点

```http
DELETE /api/question/deletetestcase?caseId=1
Authorization: Bearer <token>
```

返回：`Result<Void>`。

## service-review 收藏夹模块

基础路径：`/api/review/favorites`

注意：当前 gateway 未配置 `/api/review/**`，前端若走网关需要先补路由；否则直连 `http://localhost:8085`。

### 获取当前用户收藏夹列表

```http
GET /api/review/favorites/list
Authorization: Bearer <token>
```

返回：`Result<List<Favorites>>`。

### 获取收藏夹内题目

```http
GET /api/review/favorites/questions?favoriteId=1
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `favoriteId` | `Long` | 是 | 收藏夹 ID |

返回：`Result<List<QuestionDto>>`。

说明：

- 收藏夹必须属于当前用户。
- 服务会调用 `service-question` 批量查询题目信息。

### 获取创建收藏夹请求 ID

```http
GET /api/review/favorites/requestId
Authorization: Bearer <token>
```

返回：`Result<String>`。

说明：创建收藏夹前先获取，用于防止重复提交。

### 创建收藏夹

```http
POST /api/review/favorites?favoritesName=动态规划&requestId=<requestId>
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `favoritesName` | `String` | 是 | 收藏夹名称 |
| `requestId` | `String` | 是 | 通过 `/requestId` 获取 |

返回：`Result<Void>`。

### 添加题目到收藏夹

```http
POST /api/review/favorites/question?questionId=1&favoriteId=1
Authorization: Bearer <token>
```

返回：`Result<String>`，成功 `data` 为 `"success"`。

### 从收藏夹移除题目

```http
DELETE /api/review/favorites/question?questionId=1&favoriteId=1
Authorization: Bearer <token>
```

返回：`Result<String>`。

### 删除收藏夹

```http
DELETE /api/review/favorites?favoriteId=1
Authorization: Bearer <token>
```

返回：`Result<String>`。

### 更新收藏夹

```http
PUT /api/review/favorites
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`ReceiveDto`

```json
{
  "favoritesId": 1,
  "favoritesName": "动态规划",
  "favoritesContent": "DP 专题",
  "favoritesType": "algorithm",
  "questionIds": [1, 2, 3]
}
```

说明：

- `favoritesId` 用于定位收藏夹。
- 其余字段传 `null` 时不更新。

返回：`Result<String>`。

## service-community 社区模块

基础路径：`/api/community`，可通过网关 `http://localhost:8082` 访问。所有接口均需登录。

| 方法 | 路径 | 用途 | 返回数据 |
| --- | --- | --- | --- |
| `GET` | `/request-id` | 获取帖子或评论发布请求 ID | `String` |
| `POST` | `/posts?requestId=...` | 发布帖子，请求体为 `PostDto` | `Post` |
| `GET` | `/posts` | 游标分页查询帖子 | `CursorPageResult<HomePostVo>` |
| `GET` | `/posts/{postId}` | 查询帖子详情和相关推荐 | `PostVo` |
| `POST` | `/comments?requestId=...` | 发布评论或回复，请求体为 `CommentDto` | `Comment` |
| `GET` | `/comments` | 游标分页查询评论或指定根评论的回复 | `CursorPageResult<CommentVo>` |
| `PUT` | `/likes/posts/{postId}` | 切换帖子点赞状态 | `Boolean` |
| `PUT` | `/likes/comments/{commentId}` | 切换评论点赞状态 | `Boolean` |
| `PUT` | `/likes/solution/{solutionId}` | 切换题解点赞状态 | `Boolean` |

分页接口的 `pageSize` 默认 20，允许范围为 1 到 100；首次不传 `lastId`，后续传上页返回的 `nextCursor`。点赞返回值表示操作后的状态：`true` 为已点赞，`false` 为已取消。

完整请求示例、参数和 DTO 字段见 [社区模块接口文档](service-community-api.md)。

## service-review 复习计划模块

基础路径：`/api/review/review`，可通过网关访问，均从登录上下文获取当前用户。

| 方法 | 路径 | 用途 | 返回数据 |
| --- | --- | --- | --- |
| `POST` | `/addquestiontoreview?questionId=...` | 将题目加入长期复习计划 | `String` |
| `GET` | `/today` | 获取或生成今日复习快照 | `Map<String,Object>` |
| `GET` | `/gettodayreview` | 今日复习计划旧路径兼容 | `Map<String,Object>` |
| `GET` | `/config` | 获取当前用户复习配置 | `ReviewConfig` |
| `PUT` | `/config` | 新增或更新复习配置 | `ReviewConfig` |
| `GET` | `/allrecord` | 查询当前用户全部每日复习记录 | `List<ReviewRecord>` |
| `GET` | `/record/{reviewRecordId}` | 查询一条每日复习记录详情 | `ReviewRecordVo` |
| `PUT` | `/review/{reviewId}` | 修改长期复习项的权重或状态 | `Review` |
| `GET` | `/allreview` | 查询长期复习项及题目信息 | `List<ReviewVo>` |

完整字段和复习流程见 [复习模块接口文档](service-review-api.md)。

## service-message 通知中心模块

基础路径：`/api/message/notifications`。HTTP 请求通过网关的 `lb://service-message` 路由转发，WebSocket `/websocket/**` 使用独立 WS 路由。

| 方法 | 路径 | 用途 | 返回数据 |
| --- | --- | --- | --- |
| `GET` | `/api/message/notifications` | 倒序游标分页查询通知摘要 | `NotificationCursorPageVo` |
| `GET` | `/api/message/notifications/unread-count` | 查询未读通知数 | `Long` |
| `GET` | `/api/message/notifications/{notificationId}` | 查询详情并自动标记已读 | `NotificationDetailVo` |
| `PUT` | `/api/message/notifications/{notificationId}/read` | 标记单条通知已读 | `String` |
| `PUT` | `/api/message/notifications/read-all` | 全部已读，可选 `type` | `String` |
| `DELETE` | `/api/message/notifications/{notificationId}` | 软删除自己的通知 | `String` |

列表查询参数：

| 参数 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `lastId` | 否 | - | 首次不传，后续传 `nextCursor` |
| `pageSize` | 否 | `20` | 范围 1 到 100 |
| `type` | 否 | - | `LIKE` 或 `REVIEW` |
| `unreadOnly` | 否 | `false` | 是否只返回未读通知 |

列表响应不包含正文和扩展数据。详情响应包含 `content` 和已解析成 JSON 对象的 `extraData`。通知 ID、业务 ID、用户 ID 和下一页游标均按字符串返回。

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
GET /api/question/info/favoritequestions
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：

```json
[1, 2, 3]
```

返回：`Result<List<QuestionDto>>`。

注意：这是 `GET + RequestBody`，部分 HTTP 客户端和代理对 GET body 支持不一致；Feign 内部调用可用，前端直接调用时建议谨慎。

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

