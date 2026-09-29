# service-review 后端接口文档

## 概览

- 服务名：`service-review`
- 默认端口：`8097`
- 主要模块：收藏夹、复习计划（SM-2 间隔重复）、笔记（文件夹 + Markdown 笔记）、进度计划追踪（study-plan tracker，含周报）
- 当前已开放 HTTP 接口：收藏夹接口、复习计划接口、复习配置接口、笔记接口、进度计划接口、周报接口，以及 Agent 专用收藏夹 / 笔记 / 进度计划接口
- 统一响应结构：`Result<T>`
- 用户身份：接口通过网关/拦截器写入 `UserContext.getUserId()` 获取当前用户，不需要前端显式传 `userId`
- 鉴权：`UserAuthInterceptor` 拦截 `/**`（仅排除 `/websocket/**`、`/ws/**`、`/sockjs/**`、`/login`、`/register`），即本服务全部 HTTP 接口都要求经网关注入的 `X-User-Id`/`X-Internal-Token`；Agent 专用接口与网页端同一鉴权链路
- 限流：所有接口带 `@RateLimit` 注解（api-governance，Redis 滑动窗口，窗口均 60 秒，超限时 HTTP 仍为 200，返回 `code=4290001`，提示“复习操作过于频繁，请稍后重试”），阈值见下文各节

## 统一响应

所有接口返回 `org.example.serviceapi.dto.Result<T>`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | `Integer` | 状态码，成功为 `200`；失败为七位业务错误码（HTTP 状态码仍为 200，见下文「错误场景」） |
| `message` | `String` | 响应消息，成功默认为 `success` |
| `data` | `T` | 业务数据 |

成功示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

注意：控制器中大量写接口以 `Result.success(字符串文案)` 返回（如 `"success"`、`"创建成功"`、`"更新成功"`、`"删除成功"`、`"移动成功"`）。由于 `Result` 存在 `success(String message)` 重载，传入 String 实参时命中该重载——**文案落在 `message` 字段，`data` 为 `null`**，调用方不要从 `data` 读取这些文案。业务失败由 `Advice/GlobalExceptionHandler`（继承 `service-common` 的 `AbstractGlobalExceptionHandler`）统一处理：只有 `BizException` 携带面向用户的文案与七位业务错误码（`code` 形如 `4040301`，规则见 [`../backend-controller-api.md`](../backend-controller-api.md) 的「错误码与业务异常」一节，本服务服务号为 `03`，自有码表在 `exception/ReviewErrorCode.java`）；`IllegalArgumentException`、`IllegalStateException` 等其他异常一律换成 `CommonErrorCode` 通用中文文案（异常原始消息不再透出到前端），HTTP 状态码保持 200，客户端按 `Result.code !== 200` 分支。

## 收藏夹接口

基础路径：`/api/review/favorites`

### 获取当前用户收藏夹列表

- 方法：`GET`
- 路径：`/api/review/favorites/list`
- 限流：100 次/60 秒
- 请求参数：无
- 返回：`Result<List<Favorites>>`

返回数据字段见 `Favorites`。未登录（`UserContext.getUserId()` 为空）时抛“登录状态已失效，请重新登录”（`4010001`）。

### 获取收藏夹内题目列表

- 方法：`GET`
- 路径：`/api/review/favorites/questions`
- 限流：100 次/60 秒
- 请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `favoriteId` | `Long` | 是 | 收藏夹 ID |

- 返回：`Result<List<QuestionDto>>`

说明：

- 仅允许查询当前用户自己的收藏夹。
- 服务会通过 `service-question` 批量查询题目信息（`POST /api/question/info/favoritequestions`，返回完整 `QuestionDto`）。
- 懒删除：他人非公开题（`status != 1` 且非本人创建）会从收藏夹剔除并回写 DB。
- 若收藏夹不存在（“收藏夹不存在或已被删除”，`4040301`）、无权限（“没有权限执行该操作”，`4030001`）或下游服务返回非 `200`（“相关服务暂时无法响应，请稍后重试”，`5000003`），会抛出业务异常。

### 获取创建收藏夹请求 ID

- 方法：`GET`
- 路径：`/api/review/favorites/requestId`
- 限流：60 次/60 秒
- 请求参数：无
- 返回：`Result<String>`

说明：

- 前端创建收藏夹前先获取 `requestId`。
- `requestId` 为服务端生成的 UUID；由于命中 `success(String)` 重载，实际出现在 `message` 字段（`data` 为空）。
- 创建接口会用该 ID 做 3 分钟幂等控制，防止重复提交。

### 创建收藏夹

- 方法：`POST`
- 路径：`/api/review/favorites`
- 限流：20 次/60 秒
- 请求参数（query 参数，`@RequestParam`，非 JSON 请求体）：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `favoritesName` | `String` | 是 | 收藏夹名称 |
| `requestId` | `String` | 是 | 通过 `/requestId` 获取的请求 ID |

- 返回：`Result<String>`

成功时 `message` 为 `创建成功`（`data` 为空）；重复 `requestId` 抛“该收藏夹已创建，请勿重复提交”（`4090302`）。

### 向收藏夹添加题目

- 方法：`POST`
- 路径：`/api/review/favorites/question`
- 限流：60 次/60 秒
- 请求参数（query 参数）：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `questionId` | `Long` | 是 | 题目 ID |
| `favoriteId` | `Long` | 是 | 收藏夹 ID |

- 返回：`Result<String>`

成功返回：

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

说明：

- 收藏夹必须属于当前用户。
- 同一个收藏夹内不能重复添加同一题目。
- 添加前会通过 `service-question` 查询题目是否存在（`GET /api/question/info/{questionId}`）。

### 从收藏夹移除题目

- 方法：`DELETE`
- 路径：`/api/review/favorites/question`
- 限流：60 次/60 秒
- 请求参数（query 参数）：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `questionId` | `Long` | 是 | 题目 ID |
| `favoriteId` | `Long` | 是 | 收藏夹 ID |

- 返回：`Result<String>`

成功时 `message` 为 `"success"`（`data` 为空）。

### 删除收藏夹

- 方法：`DELETE`
- 路径：`/api/review/favorites`
- 限流：30 次/60 秒
- 请求参数（query 参数）：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `favoriteId` | `Long` | 是 | 收藏夹 ID |

- 返回：`Result<String>`

成功时 `message` 为 `"success"`（`data` 为空）。

### 更新收藏夹

- 方法：`PUT`
- 路径：`/api/review/favorites`
- 限流：30 次/60 秒
- Content-Type：`application/json`
- 请求体：`ReceiveDto`
- 返回：`Result<String>`

请求示例：

```json
{
  "favoritesId": 1,
  "favoritesName": "动态规划",
  "favoritesContent": "DP 专题题单",
  "favoritesType": "algorithm",
  "questionIds": [1001, 1002, 1003]
}
```

说明：

- `favoritesId` 必填，用于定位收藏夹。
- 其余字段为空时不更新。
- 至少更新一个字段时 `message` 返回“更新成功”；无可更新字段时返回“未更新任何字段”（`data` 为空）。

## Agent 专用收藏夹接口

基础路径：`/api/review/agent/favorites`

供 codewise-agent（`AgentFavoritesController`）调用，与网页端接口分离成类。与网页端的差异：

- **瘦身返回**：收藏夹列表不返回 `userId` 与 `questionIds` 明细（只含题目数量）；夹内题目列表经 service-question 批量瘦身接口取数，不含题干，完整题目详情由 service-question 的 `/api/question/agent/detail` 批量提供；
- **批量语义**：批量增删一次请求完成去重、存在性与可见性校验，返回分项明细；跨夹移动在事务内按主键升序对源/目标两行 `FOR UPDATE` 加行锁；
- **创建即回显 ID**：创建直接返回含 `favoritesId` 的收藏夹 VO，幂等键 `requestId` 由 agent 客户端生成（复用网页端 Redis 幂等键，3 分钟窗口）；
- **元信息更新不收 `questionIds`**：收藏夹题目列表只能通过批量增删/移动接口变更，避免整体覆盖。

### Agent：收藏夹瘦身列表

- 方法：`GET`
- 路径：`/api/review/agent/favorites`
- 请求参数：无
- 限流：100 次/60 秒
- 返回：`Result<List<FavoriteFolderVo>>`（`favoritesId`/`favoritesName`/`favoritesType`/`favoritesContent`/`questionCount`/`createTime`/`updateTime`）

### Agent：收藏夹题目瘦身列表

- 方法：`GET`
- 路径：`/api/review/agent/favorites/questions?favoriteId=`
- 限流：100 次/60 秒
- 返回：`Result<List<FavoriteQuestionBriefVo>>`（`questionId`/`title`/`difficulty`/`tags`/`totalSubmit`/`totalAc`/`passRate`）

保留网页端懒删除语义：他人私密/下架/审核中的题目从收藏夹剔除并回写。

### Agent：定位题目所在收藏夹

- 方法：`GET`
- 路径：`/api/review/agent/favorites/locate?questionIds=1,2`
- 限流：100 次/60 秒
- 请求参数：`questionIds`（去重后 1-50 个正整数 ID）
- 返回：`Result<List<FavoriteLocationVo>>`，每项含 `questionId` 与包含它的 `folders`（`favoriteId`/`favoritesName`，未被任何收藏夹包含时为空列表）

### Agent：创建收藏夹

- 方法：`POST`
- 路径：`/api/review/agent/favorites`
- Content-Type：`application/json`
- 限流：20 次/60 秒
- 请求体：`AgentFavoriteCreateDto`（`favoritesName` 必填 1-255 字符；`favoritesType`/`favoritesContent` 可选 ≤255；`requestId` 必填幂等键）
- 返回：`Result<FavoriteFolderVo>`，`data.favoritesId` 为新收藏夹 ID

重复 `requestId` 返回“该收藏夹已创建，请勿重复提交”（`4090302`）。

### Agent：更新收藏夹元信息

- 方法：`PUT`
- 路径：`/api/review/agent/favorites`
- Content-Type：`application/json`
- 限流：30 次/60 秒
- 请求体：`AgentFavoriteUpdateDto`（`favoritesId` 必填；`favoritesName`/`favoritesType`/`favoritesContent` 可选；不接受 `questionIds`）
- 返回：`Result<FavoriteFolderVo>`

### Agent：删除收藏夹

- 方法：`DELETE`
- 路径：`/api/review/agent/favorites?favoriteId=`
- 限流：30 次/60 秒
- 返回：`Result<String>`，成功时 `message` 为 `"success"`（`data` 为空）

### Agent：批量添加题目

- 方法：`POST`
- 路径：`/api/review/agent/favorites/questions`
- Content-Type：`application/json`
- 限流：20 次/60 秒
- 请求体：`AgentFavoriteBatchAddDto`（`favoriteId` 必填；`questionIds` 去重后 1-50 个）
- 返回：`Result<FavoriteAddResultVo>`

```json
{
  "added": 2,
  "skippedDuplicateIds": [1],
  "skippedInvisibleIds": [3],
  "invalidQuestionIds": [4]
}
```

只有 `status=1` 或本人创建的题目会真正写入（不可见题前置拦截，比网页端更严格）。

### Agent：批量移除题目

- 方法：`DELETE`
- 路径：`/api/review/agent/favorites/questions?favoriteId=&questionIds=1,2`
- 限流：30 次/60 秒
- 返回：`Result<FavoriteRemoveResultVo>`（`removed`/`notInFolderIds`）

### Agent：跨收藏夹移动题目

- 方法：`POST`
- 路径：`/api/review/agent/favorites/move`
- Content-Type：`application/json`
- 限流：30 次/60 秒
- 请求体：`AgentFavoriteMoveDto`（`fromFavoriteId`/`toFavoriteId` 必填且不同；`questionIds` 去重后 1-50 个）
- 返回：`Result<FavoriteMoveResultVo>`（`moved`/`notInSourceIds`/`alreadyInTargetIds`）

并发安全：事务内先按主键升序对源/目标两行 `FOR UPDATE` 加行锁（`FavoritesMapper.lockByIdsForUpdate`），锁内读改写；已同时在两侧的题目保留在源夹中。

## 笔记接口（网页端）

基础路径：`/api/review/note`（`NoteController`）。一级文件夹（分类）+ 其下 Markdown 笔记的增删改查；`userId` 一律取自 `UserContext`，归属校验在 service 层。表 `note_folder`、`note` 由 `NoteSchemaInitializer` 启动时幂等建表（结构亦沉淀于 `resources/migration/V4__note.sql`）。

`folderId` 语义：创建/移动笔记时缺省、空串或 `0` → 未分类（`folder_id IS NULL`），正数 → 目标文件夹（校验归属），负数 → 非法参数。内容类型当前仅 `MD`（`MarkdownNoteContentHandler`），创建后不可变；正文上限 1 MiB，标题 ≤255 字符，为空时从正文推导（首个标题/首行），兜底“未命名笔记”。

| 方法 | 路径 | 限流 | 说明 | 返回 |
| --- | --- | --- | --- | --- |
| `GET` | `/folders` | 100/60s | 我的文件夹列表（含笔记数，未分类不计入） | `Result<List<NoteFolderVo>>` |
| `POST` | `/folders` | 30/60s | 创建文件夹，请求体 `NoteFolderSaveDto`（`folderName` 必填 1-255 字符；`requestId` 可选幂等键，3 分钟窗口）；同一用户下名称唯一 | `Result<NoteFolderVo>` |
| `PUT` | `/folders/{folderId}` | 30/60s | 重命名文件夹（`requestId` 忽略） | `Result<NoteFolderVo>` |
| `DELETE` | `/folders/{folderId}` | 30/60s | 删除文件夹，级联删除其下全部笔记（同一事务） | `Result<String>`（message=`删除成功`） |
| `GET` | `/list?folderId=&cursor=&size=` | 100/60s | 笔记列表，游标分页（`note_id` 倒序）；`size` 默认 20、上限 100；返回预览（去 Markdown 标记截断 120 字符），不含全文 | `Result<NoteListVo>` |
| `GET` | `/{noteId}` | 100/60s | 笔记详情（全文） | `Result<NoteDetailVo>` |
| `POST` | `/` | 30/60s | 创建笔记，请求体 `NoteCreateDto`（`content` 必填；`title`/`folderId`/`type`/`requestId` 可选） | `Result<NoteDetailVo>` |
| `PUT` | `/{noteId}` | 30/60s | 更新标题/正文，请求体 `NoteUpdateDto`（至少一项非空，否则报“没有需要更新的内容，请修改后重试”，`4000310`） | `Result<NoteDetailVo>` |
| `POST` | `/{noteId}/move?folderId=` | 30/60s | 移动笔记；`folderId` 缺省/空串/`0` 表示移到未分类 | `Result<String>`（message=`移动成功`） |
| `DELETE` | `/{noteId}` | 30/60s | 删除笔记 | `Result<String>`（message=`删除成功`） |

VO 字段：`NoteFolderVo`（`folderId`/`folderName`/`noteCount`/`createTime`/`updateTime`）；`NoteListVo`（`items`/`nextCursor`/`hasNext`/`total`，item 为 `NoteBriefVo`：`noteId`/`title`/`folderId`/`contentType`/`preview`/`createTime`/`updateTime`）；`NoteDetailVo`（`noteId`/`title`/`folderId`/`contentType`/`content`/`createTime`/`updateTime`，均不含 `userId`）。

## 笔记 Agent 接口

基础路径：`/api/review/agent/note`（`AgentNoteController`），薄委托同一组 service，业务语义与网页端完全一致；差异仅在于路径参数一律改为 query 参数，以适配 agent 工具的静态路径白名单。

| 方法 | 路径 | 限流 | 对应网页端 |
| --- | --- | --- | --- |
| `GET` | `/folders` | 100/60s | `GET /folders` |
| `POST` | `/folders` | 30/60s | `POST /folders` |
| `PUT` | `/folders?folderId=` | 30/60s | `PUT /folders/{folderId}` |
| `DELETE` | `/folders?folderId=` | 30/60s | `DELETE /folders/{folderId}` |
| `GET` | `/list?folderId=&cursor=&size=` | 100/60s | `GET /list` |
| `GET` | `/detail?noteId=` | 100/60s | `GET /{noteId}` |
| `POST` | `/` | 30/60s | `POST /` |
| `PUT` | `/?noteId=` | 30/60s | `PUT /{noteId}` |
| `POST` | `/move?noteId=&folderId=` | 30/60s | `POST /{noteId}/move` |
| `DELETE` | `/?noteId=` | 30/60s | `DELETE /{noteId}` |

返回类型与网页端同名接口一致（`NoteFolderVo` / `NoteListVo` / `NoteDetailVo` / `Result<String>`）。

## 学习计划进度跟踪接口（网页端）

基础路径：`/api/review/progress`（`ProgressTrackerController`）。用户自定义题单，按日期学习跟进；每条计划对应「某用户 × 某题目」唯一（DB 唯一键 `uk_tracker_user_question`），重复创建报错。

状态机单向：`0-未开始 → 1-进行中 →（2-已完成 或 3-已过期）`，状态流转为查询时懒刷新（无常驻定时任务）；判题 AC 经 MQ 置为已完成。

| 方法 | 路径 | 限流 | 说明 | 返回 |
| --- | --- | --- | --- | --- |
| `POST` | `/create` | 30/60s | 创建计划，请求体 `ProgressTrackerDto`（`questionId`、`beginTime` 必填且 `beginTime` 不早于今天，`notesContent` 可选）；当天创建即为进行中；同题已存在报“该题目已存在进度计划，请勿重复创建”（`4090303`） | `Result<ProgressTracker>` |
| `GET` | `/list?date=2026-08-29` | 100/60s | 按日期精确查询当天计划（`date` 必填，返回前懒刷新状态） | `Result<List<ProgressTracker>>` |
| `GET` | `/calendar/day?date=` | 100/60s | 单日聚合：计划题数与完成数（`date` 缺省为今天） | `Result<List<ProgressCalendarVo>>` |
| `GET` | `/calendar/week?date=` | 100/60s | 锚点日期所在自然周（周一至周日）7 天逐日聚合 | `Result<List<ProgressCalendarVo>>` |
| `GET` | `/calendar/month?date=` | 100/60s | 锚点日期所在自然月逐日聚合（28-31 天） | `Result<List<ProgressCalendarVo>>` |
| `POST` | `/update?progressId=&beginTime=&notesContent=&summaryContent=` | 30/60s | query 参数；备注/总结任何状态可改；开始时间仅「未开始」可改且不得早于今天，改期后按新日期重算初始状态 | `Result<String>`（message=`success`） |
| `POST` | `/delete?progressId=` | 30/60s | 删除自己的计划（已过期只能删除后重建） | `Result<String>`（message=`success`） |
| `GET` | `/report/week?date=` | 100/60s | 周报，见下 | `Result<WeeklyReportVo>` |

`ProgressCalendarVo`：`date`/`total`（当天计划题数）/`completed`（当天已完成题数）。

## 学习计划进度跟踪 Agent 接口

基础路径：`/api/review/agent/progress`，供 codewise-agent（`AgentProgressTrackerController`）调用；与网页端共用同一组 service，差异是瘦身 VO、批量语义（幂等创建/静默删除）与 query 参数化路径（适配 agent 工具静态路径白名单）。

状态机单向：`0-未开始 → 1-进行中 →（2-已完成 或 3-已过期）`。开始时间仅在「未开始」可改，备注/反思总结（`notesContent`/`summaryContent`）任何状态可改；已过期只能删除或新建。批量接口单批上限 200 条。

| 方法 | 路径 | 限流 | 说明 | 返回 |
| --- | --- | --- | --- | --- |
| `GET` | `/today` | 100/60s | 今天要做的计划（瘦身，题目标题/难度经批量 Feign 回填，备注/反思截断 100 字符，不含 submitIds；懒刷新状态） | `Result<List<ProgressTrackerBriefVo>>` |
| `GET` | `/list` | 100/60s | 未开始 + 进行中（接下来要做），按 `beginTime` 升序，瘦身返回 | `Result<List<ProgressTrackerBriefVo>>` |
| `GET` | `/detail?progressId=` | 100/60s | 单条详情（含 submitIds 与完整备注/反思） | `Result<ProgressTracker>` |
| `GET` | `/calendar?range=day\|week\|month&date=` | 100/60s | 日历逐日计划/完成数；`range` 缺省 `week`，其余取值报“日历统计范围仅支持按天、按周或按月”（`4000321`） | `Result<List<ProgressCalendarVo>>` |
| `POST` | `/create` | 30/60s | 批量创建，请求体为 `ProgressTrackerDto` JSON 数组；幂等：同题已存在记入 `skippedQuestionIds` 不报错 | `Result<ProgressBatchCreateVo>`（`created`/`skippedQuestionIds`） |
| `POST` | `/update?progressId=&beginTime=&notesContent=&summaryContent=` | 30/60s | 更新备注/反思总结/开始时间（单向状态机校验，同网页端） | `Result<String>`（message=`success`） |
| `POST` | `/delete?progressIds=1,2` | 30/60s | 批量删除；不存在或非本人的 ID 记入 `notFoundIds`，不外泄他人数据 | `Result<ProgressBatchDeleteVo>`（`deleted`/`notFoundIds`） |

`ProgressTrackerBriefVo`：`progressId`/`questionId`/`title`/`difficulty`/`beginTime`/`status`/`notesContent`/`summaryContent`。

### 周报（前端与 agent 共用）

- 方法：`GET`
- 路径：`/api/review/progress/report/week?date=`
- 限流：100 次/60 秒；`date` 可选，缺省为今天
- 返回：`Result<WeeklyReportVo>`（`startDate`/`endDate` + 本周计划总数 `totalPlanned`/`completedCount`/`inProgressCount`/`expiredCount` + 逐日分布 `daily` + 已完成条目 `completedItems`，每项含 `questionId`/`title`/`summaryContent`，标题经题目瘦身 Feign 批量回填）
- 读时聚合，不落统计表；锚点日期所在自然周（周一至周日）。

## DTO 与实体字段

### ReceiveDto

路径：`service-review/src/main/java/org/example/servicereview/dto/ReceiveDto.java`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `favoritesId` | `Long` | 收藏夹 ID，更新时必填 |
| `favoritesName` | `String` | 收藏夹名称 |
| `favoritesContent` | `String` | 收藏夹描述 |
| `questionIds` | `List<Long>` | 收藏夹内题目 ID 列表 |
| `favoritesType` | `String` | 收藏夹类型 |

### Favorites

路径：`service-review/src/main/java/org/example/servicereview/entry/Favorites.java`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `favoritesId` | `Long` | 收藏夹 ID |
| `favoritesName` | `String` | 收藏夹名称 |
| `favoritesType` | `String` | 收藏夹类型 |
| `favoritesContent` | `String` | 收藏夹描述 |
| `userId` | `Long` | 所属用户 ID |
| `questionIds` | `List<Long>` | 题目 ID 列表，数据库为 JSON |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |

### ProgressTracker

路径：`service-review/src/main/java/org/example/servicereview/entry/ProgressTracker.java`（表 `progress_tracker`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `progressId` | `Long` | 计划 ID |
| `userId` | `Long` | 所属用户 ID |
| `questionId` | `Long` | 题目 ID（与 userId 联合唯一） |
| `submitIds` | `List<Long>` | 当天绑定到该计划的提交记录 ID 集合，数据库为 JSON；存在 AC 提交即置为已完成 |
| `status` | `Integer` | 0-未开始、1-进行中、2-已完成、3-已过期（单向状态机，查询时懒刷新） |
| `notesContent` | `String` | 学习计划备注 |
| `summaryContent` | `String` | 完成后的反思总结 |
| `beginTime` | `LocalDate` | 计划开始日期（创建不得早于今天；仅「未开始」可改） |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |

创建请求体 `ProgressTrackerDto`：`questionId`（必填）、`beginTime`（必填，不早于今天）、`notesContent`（可选）。

### QuestionDto

路径：`service-api/src/main/java/org/example/serviceapi/dto/QuestionDto.java`

收藏夹题目列表返回该 DTO。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `questionId` | `Long` | 题目 ID |
| `title` | `String` | 标题 |
| `description` | `String` | 题目描述 |
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
| `status` | `Integer` | 题目状态，`0` 下架、`1` 正常、`2` 审核、`3` 私密 |
| `aiStatue` | `String` | AI 处理状态 |
| `createUserId` | `Long` | 创建用户 ID |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |
| `totalSubmit` | `Long` | 总提交数 |
| `totalAc` | `Long` | AC 数 |
| `passRate` | `BigDecimal` | 通过率 |
| `contentHash` | `String` | 内容哈希 |

### QuestionBriefDto

路径：`service-api/src/main/java/org/example/serviceapi/dto/question/QuestionBriefDto.java`

service-question 内部批量瘦身接口（`POST /api/question/info/briefquestions`）与 Agent 收藏夹题目列表使用的瘦身 DTO，不含题干/样例等大字段。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `questionId` | `Long` | 题目 ID |
| `title` | `String` | 标题 |
| `difficulty` | `Integer` | 难度 |
| `tags` | `String` | 标签 |
| `status` | `Integer` | 题目状态，供调用方做可见性过滤 |
| `createUserId` | `Long` | 创建用户 ID，供调用方做可见性过滤 |
| `totalSubmit` | `Long` | 总提交数 |
| `totalAc` | `Long` | AC 数 |
| `passRate` | `BigDecimal` | 通过率 |
| `createTime` | `LocalDateTime` | 创建时间 |

## 复习计划接口

基础路径：`/api/review/review`

### 获取今日复习计划

- 方法：`GET`
- 路径：`/api/review/review/today`
- 限流：100 次/60 秒
- 请求参数：无
- 返回：`Result<Map<String,Object>>`

返回的 Map 含两个键：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `reviewRecord` | `ReviewRecord` | 当天复习快照（新建且无到期题、或并发兜底重查路径下可能缺失该键） |
| `questions` | `List<QuestionDto>` | 待复习题目详情列表（当天首次生成快照时为到期题目列表） |

说明：

- 返回当前用户今天复习快照中的**待复习**题目详情（经 `POST /api/question/info/favoritequestions` 批量查询）；已完成题目明细在 `reviewRecord.completedReviewQuestionIds` 中，不再拉取详情。
- 如果当天还没有复习快照，会根据用户复习配置和到期的 `Review` 记录生成当天 `ReviewRecord`（按 `weight desc, next_review_time asc, update_time asc` 取前 `reviewCount` 条）。
- 如果今天没有需要复习的题目，`questions` 为空列表。

成功示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "reviewRecord": { "reviewRecordId": 1, "pendingReviewQuestionIds": [1001], "completedReviewQuestionIds": [], "reviewDate": "2026-08-29" },
    "questions": [
      { "questionId": 1001, "title": "两数之和", "difficulty": 1, "tags": "数组,哈希表" }
    ]
  }
}
```

### 获取今日复习计划（旧路径兼容）

- 方法：`GET`
- 路径：`/api/review/review/gettodayreview`
- 限流：100 次/60 秒
- 请求参数：无
- 返回：`Result<Map<String,Object>>`

说明：

- 该接口为旧路径兼容接口，内部逻辑与 `/api/review/review/today` 相同。
- 新前端建议优先使用 `/today`。

## 复习配置接口

基础路径：`/api/review/review`

### 获取当前用户复习配置

- 方法：`GET`
- 路径：`/api/review/review/config`
- 限流：100 次/60 秒
- 请求参数：无
- 返回：`Result<ReviewConfig>`

说明：

- 获取当前用户的复习配置。
- 如果当前用户还没有配置记录，服务会自动创建一份默认配置。

成功示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "reviewConfigId": 1,
    "userId": 10,
    "reviewCount": 20,
    "enableAutoReview": 1,
    "countCompileError": 1,
    "minEasinessFactor": 1.30,
    "initialEasinessFactor": 2.50,
    "masteredIntervalDays": 30,
    "createTime": "2026-07-07T18:00:00",
    "updateTime": "2026-07-07T18:00:00"
  }
}
```

### 新增或更新当前用户复习配置

- 方法：`PUT`
- 路径：`/api/review/review/config`
- 限流：20 次/60 秒
- Content-Type：`application/json`
- 请求体：`ReviewConfigDto`
- 返回：`Result<ReviewConfig>`

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

说明：

- 如果用户还没有配置记录，则会创建配置（请求中为空的字段取默认值：`reviewCount=2147483647` 不限制、`enableAutoReview=1`、`countCompileError=1`、`minEasinessFactor=1.30`、`initialEasinessFactor=2.50`、`masteredIntervalDays=30`）。
- 如果配置已存在，则只更新请求体中非空字段。
- 参数校验（`ReviewService#validateReviewConfig`）：`reviewCount` 小于 0 直接拒绝（“每日复习数量不能小于 0”，`4000326`）；`enableAutoReview`、`countCompileError` 只能是 `0` 或 `1`；`minEasinessFactor` 不得低于 1.3；`initialEasinessFactor` 不得低于 `minEasinessFactor`；`masteredIntervalDays` 必须大于 0。
- `enableAutoReview`：`1` 表示开启自动复习推进，`0` 表示关闭（关闭后判题消息到达时直接 ACK 跳过，不推进 SM-2）。
- `countCompileError`：`1` 表示 CE 计入复习并按 `quality=0` 处理，`0` 表示 CE 不计入本次复习。

## 复习记录与长期复习项接口

基础路径：`/api/review/review`

| 方法 | 路径 | 说明 | 返回 |
| --- | --- | --- | --- |
| `POST` | `/addquestiontoreview?questionId=...` | 将题目加入当前用户长期复习计划（同题重复添加报“该题目已在您的复习计划中，请勿重复添加”，`4090306`） | `Result<String>`（message=`success`） |
| `GET` | `/allrecord` | 查询当前用户全部每日复习快照（按 `create_time` 倒序） | `Result<List<ReviewRecord>>` |
| `GET` | `/record/{reviewrecordId}` | 查询一条快照并聚合题目信息 | `Result<ReviewRecordVo>` |
| `GET` | `/reviewrecord?day=2026-08-29` | 查询指定日期的复习快照并聚合题目信息 | `Result<ReviewRecordVo>` |
| `PUT` | `/review/{reviewId}` | 修改权重/状态/下次复习时间 | `Result<Review>` |
| `GET` | `/allreview` | 查询长期复习项并聚合题目信息（按 `last_review_time` 倒序） | `Result<List<ReviewVo>>` |
| `DELETE` | `/review/{reviewId}` | 删除当前用户自己的长期复习项 | `Result<String>`（message=`success`） |

限流（次/60 秒）：`/addquestiontoreview` 60、`/allrecord` 100、`/record/{reviewrecordId}` 100、`/reviewrecord` 100、`PUT /review/{reviewId}` 120、`/allreview` 100、`DELETE /review/{reviewId}` 30。

修改长期复习项请求体 `UpdateReviewDto`：

```json
{
  "weight": 1,
  "status": 2,
  "nextReviewData": "2026-09-05T10:00:00"
}
```

`PUT /review/{reviewId}` 校验（`ReviewService#updateReview`）：

- 只允许操作当前用户自己的复习项（否则 `4030001` “没有权限执行该操作”）；题目若已在今日复习快照（pending 或 completed）中，报“该题目已列入今日复习，暂不支持修改”（`4090307`）。
- `status` 只接受 `0-学习中` 或 `2-暂停`；已掌握（`1`）的题目不能再改回，报“该题目已掌握，无需再修改”（`4090308`）。
- `weight` 只接受 `0`（默认）或 `1`（优先展示），其余报“复习权重设置不正确，仅支持 0 或 1”（`4000323`）。
- `nextReviewData` 可选，仅当晚于当前时间时生效（注意字段名就是 `nextReviewData`）。

`ReviewRecordVo`（含 `reviewRecordId`/`allQuestionIds`/`allQuestionTitles`（题目 ID→标题映射）/`pendingReviewQuestionIds`/`completedReviewQuestionIds`/`acQuestionIds`/`reviewDays`/`reviewDate`/`createTime`）和 `ReviewVo`（复习实体字段 + `questionTitle`，`userId` 序列化为字符串）用于向前端提供题目信息与复习状态的组合结果，避免直接暴露仅含题目 ID 的内部快照。

## 每日复习提醒

`ReviewMessageTask` 提供两个多实例安全的定时任务：

| 时间 | 查询对象 | 提醒类型 |
| --- | --- | --- |
| 每天 10:00（Asia/Shanghai） | 当天有到期题目（`enable_auto_review=1`）但尚未创建 `review_record` 的用户 | `NOTRECORD` |
| 每天 21:00（Asia/Shanghai） | 已创建当日记录且待复习列表（`pending_review_question_ids`）仍不为空的用户 | `HAVERECORD` |

任务通过 Redisson 日期锁（`task:review-reminder:{日期}:MORNING` / `task:review-reminder:{日期}NIGHT`）避免多实例重复执行。提醒不再裸直发 RabbitMQ，而是经事务性 Outbox（OutboxPro，`outboxpro_outbox` 表）以 `REVIEW_REMINDER` 事件登记，由 Relay 至少一次投递到 `notification.exchange` / `notification.review.routing`；载荷为 `NotificationDto`（`type=REVIEW`、`businessType=REVIEW_RECORD`，`extraData` 为含 `ReminderType` 与待复习数的 `NotificationReviewMqDto`）。`messageId` 形如 `review-reminder:{not_record|have_record}:{userId}:{yyyy-MM-dd}` 按天幂等，消费端（service-message 通知中心）以 messageId 幂等入库并完成 WebSocket 推送。每个用户单独一次小事务登记。

## 复习模块核心能力

- `ReviewService#getAllQuestions()`：获取当前用户今天复习题目，返回 `{reviewRecord, questions}` 组成的 Map；如果今天没有复习记录，会按配置生成当天 `ReviewRecord` 快照。
- `ReviewService#updateReviewConfig(ReviewConfigDto reviewConfigDto)`：新增或更新当前用户复习配置。
- `ReviewService#calculateNextReviewInterval(Review review, Integer quality, ReviewConfig reviewConfig)`：按 SM-2 算法和用户配置更新复习间隔、难度因子、下次复习时间；`quality` 由判题结果换算（AC=5；通过率 ≥80%/60%/30%/>0 依次 4/3/2/1；全错或 CE 计入时为 0；不计入为 -1），新 `intervalDays` 达到 `masteredIntervalDays` 时置 `status=1` 已掌握。

### Review

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `reviewId` | `Long` | 复习记录 ID |
| `userId` | `Long` | 用户 ID |
| `questionId` | `Long` | 题目 ID |
| `weight` | `Integer` | 权重，默认 `0` |
| `easinessFactor` | `BigDecimal` | SM-2 难度因子，默认 `2.5` |
| `repetitions` | `Integer` | 连续成功复习次数 |
| `intervalDays` | `Integer` | 当前复习间隔天数 |
| `lastQuality` | `Integer` | 上次复习质量评分，范围 `0-5` |
| `lastReviewTime` | `LocalDateTime` | 上次复习时间 |
| `nextReviewTime` | `LocalDateTime` | 下次复习时间 |
| `reviewCount` | `Integer` | 累计复习次数 |
| `status` | `Integer` | 状态，`0` 学习中、`1` 已掌握、`2` 暂停 |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |

### ReviewConfig

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `reviewConfigId` | `Long` | 配置 ID |
| `userId` | `Long` | 用户 ID |
| `reviewCount` | `Integer` | 每日最大复习题数 |
| `enableAutoReview` | `Integer` | 是否开启自动复习，`1` 开启、`0` 关闭 |
| `countCompileError` | `Integer` | 编译错误是否计入复习 |
| `minEasinessFactor` | `BigDecimal` | 最低难度因子 |
| `initialEasinessFactor` | `BigDecimal` | 初始难度因子 |
| `masteredIntervalDays` | `Integer` | 掌握判定间隔天数 |
| `createTime` | `LocalDateTime` | 创建时间 |
| `updateTime` | `LocalDateTime` | 更新时间 |

### ReviewRecord

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `reviewRecordId` | `Long` | 每日复习记录 ID |
| `userId` | `Long` | 用户 ID |
| `pendingReviewQuestionIds` | `List<Long>` | 当天待复习题目 ID 列表 |
| `completedReviewQuestionIds` | `List<Long>` | 当天已完成复习题目 ID 列表 |
| `createTime` | `LocalDateTime` | 创建时间 |
| `reviewDays` | `Integer` | 连续复习天数 |
| `acQuestionIds` | `List<Long>` | 当天 AC 的题目 ID 列表 |
| `reviewDate` | `LocalDate` | 复习日期 |

## 消息队列与 OutboxPro

队列/交换机/routing key 常量集中在 `service-common` 的 `MqContexts`；消费者全部手动 ACK（`application.yaml` 显式配置 `spring.rabbitmq.listener.simple.acknowledge-mode: manual`），且需 `codewise.mq.enabled: true` 才会声明拓扑。

消费（判题结果推进复习/计划）：

| 队列 | 交换机 | routing key | 监听方法 | 行为 |
| --- | --- | --- | --- | --- |
| `reviews.queue` | `reviews.exchange` | `reviews.judge.record.routing` | `ReviewService#setReview` | 按 SM-2 推进该题 `Review` 状态并同步更新当天 `ReviewRecord` 的 pending/completed/ac 列表 |
| `reviews.plan.queue` | `reviews.exchange` | `plan.judge.record.routing` | `ProgressTrackerService#setPlan` | 把 `submitRecordId` 去重追加进当天计划的 `submit_ids`，判题 AC 时置计划为已完成（2）；无当天计划则跳过 |

要点：

- 载荷均为 `ReviewJudgeRecordDto`，经 `EnvelopeCodec.unwrap` 做「统一信封 / 裸 DTO」双读，与 OutboxPro 消息线兼容。
- 复习消费的事件级幂等：以 `review:judge:{judgeRecordId}` 为幂等键写 `consumed_event` 表（PROCESSING→COMPLETED，与业务同事务；COMPLETED 是唯一跳过态）；关闭自动复习（`enableAutoReview=0`）或本次不计入复习（如 CE 且 `countCompileError=0`）时直接 ACK 跳过。
- 并发保护：Redisson 锁 `review:record:{userId}:{当天}`（复习）/ `review:plan:{userId}:{当天}`（计划），JSON 列表读改写串行化。
- 失败重试：按消息体 MD5 在 Redis Hash 计数，最多 3 次 requeue 重试；超限后消息体登记进失败 Hash 并 `nack(requeue=false)`；成功后清除计数。

生产（事务性 Outbox，OutboxPro）：

- `outboxpro.enabled: true`，`outboxpro.producer.poll-interval: 1000`（纯毫秒，Boot 3.2.4 必需）；事件路由登记在 `config/OutboxEventRouteConfig.java`。

| 事件类型 | 载荷 | 目标路由 | 触发点 |
| --- | --- | --- | --- |
| `REVIEW_REMINDER` | `NotificationDto` | `notification.exchange` / `notification.review.routing` | 每日复习提醒定时任务（每用户一次小事务） |
| `REVIEW_MASTERED` | `ReviewMasteredDto` | `notification.exchange` / `notification.review.mastered.routing` | 复习状态首次 0-学习中 → 1-已掌握，与 SM-2 更新同事务登记；`messageId=review:mastered:{userId}:{questionId}` |

## 涉及数据表

| 表 | 说明 | 建表来源 |
| --- | --- | --- |
| `favorites` | 收藏夹，`question_ids` 为 JSON 数组 | `resources/sql/sql.sql` |
| `review` | 长期复习项（SM-2 参数），唯一键 `(user_id, question_id)` | `resources/sql/sql.sql` |
| `review_config` | 用户复习配置，唯一键 `user_id` | `resources/sql/sql.sql` |
| `review_record` | 每日复习快照，唯一键 `(user_id, review_date)`，三个 JSON 列表列 | `resources/sql/sql.sql` |
| `consumed_event` | MQ 消费幂等台账：`event_id` 唯一、`routing_key`、`status`（PROCESSING/COMPLETED/FAILED）、`retry_count`、`last_error` | `resources/sql/sql.sql` |
| `progress_tracker` | 进度计划：`submit_ids` JSON、`status` 0-3、`notes_content`、`summary_content`、`begin_time` DATE，唯一键 `(user_id, question_id)` | `sql.sql` + `migration/V3__progress_tracker.sql` |
| `note_folder` | 笔记文件夹（一级），唯一键 `(user_id, folder_name)` | 启动幂等建表 `NoteSchemaInitializer` + `migration/V4__note.sql` |
| `note` | 笔记：`folder_id` 可空（NULL=未分类）、`content` mediumtext、`content_type` 默认 MD | 同上 |
| `outboxpro_outbox` | OutboxPro 事件登记表（库自管） | OutboxPro starter |

## 下游依赖

`service-review` 通过 `service-api` 的 `QuestionFeignClient` 调用 `service-question`（Feign 请求经 `FeignRequestInterceptor` 回传身份头）：

| 方法 | 下游路径 | 说明 |
| --- | --- | --- |
| `getQuestionInfo(Long questionId)` | `GET /api/question/info/{questionId}` | 查询单个题目信息（网页端向收藏夹加题前的存在性校验） |
| `getFavorites(List<Long> questionIds)` | `POST /api/question/info/favoritequestions` | 批量查询题目信息（收藏夹题目列表、今日复习、复习记录/长期复习聚合） |
| `getFavoritesBrief(List<Long> questionIds)` | `POST /api/question/info/briefquestions` | 批量查询题目瘦身信息 `QuestionBriefDto`（Agent 收藏夹题目列表、批量加题校验、进度计划标题/难度回填） |

## 错误场景

业务错误由 `Advice/GlobalExceptionHandler` 统一处理（`@RestControllerAdvice(basePackages = "...controller")`，继承 `service-common` 的 `AbstractGlobalExceptionHandler`）：service 层抛 `BizException(错误码枚举)`，其文案与七位错误码原样返回；`IllegalArgumentException`、`IllegalStateException` 等其余异常一律归一为 `CommonErrorCode` 通用文案（原始异常消息只进服务端日志，不透出前端），`DataAccessException` → `5000002`「数据处理失败，请稍后重试」，未知异常 → `5000001`「服务繁忙，请稍后重试」。失败时 HTTP 状态码仍为 200，客户端按 `Result.code !== 200` 分支。本服务码表见 `exception/ReviewErrorCode.java`（服务号 `03`）；编码规则与通用映射见 [`../backend-controller-api.md`](../backend-controller-api.md) 的「错误码与业务异常」一节。典型场景（括注为实际错误码）：

- 收藏夹不存在（“收藏夹不存在或已被删除”，`4040301`）/ 不属于当前用户（“没有权限执行该操作”，`4030001`）
- 收藏夹中已存在指定题目（“该题目已在此收藏夹中，请勿重复添加”，`4090301`）/ 不存在指定题目（“该题目不在此收藏夹中”，`4040302`）
- 请求 ID 重复使用（“该收藏夹已创建，请勿重复提交”，`4090302`）
- 下游 `service-question` 返回非 `200`（“相关服务暂时无法响应，请稍后重试”，`5000003`）
- 笔记/文件夹不存在或无权操作（“笔记不存在或已被删除”`4040303`、“文件夹不存在或已被删除”`4040304`、“没有权限执行该操作”`4030001` 等）
- 进度计划不存在或越权（“进度计划不存在或已被删除”`4040305`、“没有权限执行该操作”`4030001`）；非「未开始」改开始时间（“该计划已开始或已过期，无法再修改开始日期，请刷新后重试”，`4090304`）、开始时间早于今天（“开始日期不能早于今天，请选择今天或之后的日期”，`4000316`）
- 批量接口超限（收藏夹单次 ≤50 题——“单次最多操作 50 道题目”，`4000304`；进度计划单次 ≤200 条——“单次最多提交 200 条计划”，`4000318`）
- 限流超限：HTTP 仍为 200，返回 `code=4290001`，提示“复习操作过于频繁，请稍后重试”
