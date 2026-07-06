# service-review 后端接口文档

## 概览

- 服务名：`service-review`
- 默认端口：`8085`
- 主要模块：收藏夹、复习计划
- 当前已开放 HTTP 接口：收藏夹接口
- 统一响应结构：`Result<T>`
- 用户身份：接口通过网关/拦截器写入 `UserContext.getUserId()` 获取当前用户，不需要前端显式传 `userId`

## 统一响应

所有接口返回 `org.example.serviceapi.dto.Result<T>`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | `Integer` | 状态码，成功为 `200` |
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

## 收藏夹接口

基础路径：`/api/review/favorites`

### 获取当前用户收藏夹列表

- 方法：`GET`
- 路径：`/api/review/favorites/list`
- 请求参数：无
- 返回：`Result<List<Favorites>>`

返回数据字段见 `Favorites`。

### 获取收藏夹内题目列表

- 方法：`GET`
- 路径：`/api/review/favorites/questions`
- 请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `favoriteId` | `Long` | 是 | 收藏夹 ID |

- 返回：`Result<List<QuestionDto>>`

说明：

- 仅允许查询当前用户自己的收藏夹。
- 服务会通过 `service-question` 批量查询题目信息。
- 若收藏夹不存在、无权限或下游服务返回非 `200`，会抛出业务异常。

### 获取创建收藏夹请求 ID

- 方法：`GET`
- 路径：`/api/review/favorites/requestId`
- 请求参数：无
- 返回：`Result<String>`

说明：

- 前端创建收藏夹前先获取 `requestId`。
- 创建接口会用该 ID 做 3 分钟幂等控制，防止重复提交。

### 创建收藏夹

- 方法：`POST`
- 路径：`/api/review/favorites`
- 请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `favoritesName` | `String` | 是 | 收藏夹名称 |
| `requestId` | `String` | 是 | 通过 `/requestId` 获取的请求 ID |

- 返回：`Result<Void>`

成功时 `message` 为 `success`，当前实现的 `data` 为空。

### 向收藏夹添加题目

- 方法：`POST`
- 路径：`/api/review/favorites/question`
- 请求参数：

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
  "data": "success"
}
```

说明：

- 收藏夹必须属于当前用户。
- 同一个收藏夹内不能重复添加同一题目。
- 添加前会通过 `service-question` 查询题目是否存在。

### 从收藏夹移除题目

- 方法：`DELETE`
- 路径：`/api/review/favorites/question`
- 请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `questionId` | `Long` | 是 | 题目 ID |
| `favoriteId` | `Long` | 是 | 收藏夹 ID |

- 返回：`Result<String>`

成功时 `data` 为 `"success"`。

### 删除收藏夹

- 方法：`DELETE`
- 路径：`/api/review/favorites`
- 请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `favoriteId` | `Long` | 是 | 收藏夹 ID |

- 返回：`Result<String>`

成功时 `data` 为 `"success"`。

### 更新收藏夹

- 方法：`PUT`
- 路径：`/api/review/favorites`
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
- 至少更新一个字段时返回“更新成功”；无可更新字段时返回“未更新任何字段”。

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

## 复习模块当前状态

当前源码中已有复习计划相关 Service、实体和 Mapper，但尚未开放 HTTP Controller。

### 已有核心能力

- `ReviewService#getAllQuestions()`：获取当前用户今天待复习题目；如果今天没有复习记录，会按配置生成当天 `ReviewRecord`。
- `ReviewService#calculateNextReviewInterval(Review review, Integer quality)`：按 SM-2 算法更新复习间隔、难度因子、下次复习时间。

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

## 下游依赖

`service-review` 通过 `QuestionFeignClient` 调用 `service-question`：

| 方法 | 下游路径 | 说明 |
| --- | --- | --- |
| `getQuestionInfo(Long questionId)` | `GET /api/question/info/{questionId}` | 查询单个题目信息 |
| `getFavorites(List<Long> questionIds)` | `GET /api/question/info/favoritequestions` | 批量查询收藏夹题目信息 |

## 错误场景

当前实现主要通过抛出异常表示业务错误，典型场景：

- 收藏夹不存在
- 收藏夹不属于当前用户
- 收藏夹中已存在指定题目
- 收藏夹中不存在指定题目
- 请求 ID 重复使用
- 下游 `service-question` 返回非 `200`

