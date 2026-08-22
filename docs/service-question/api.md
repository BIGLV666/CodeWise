# service-question 题目、函数题与判题接口

本文档整理题目服务的题目、函数题、提交判题和测试点接口。统一鉴权和响应约定见 [`../backend-controller-api.md`](../backend-controller-api.md)。

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

返回：`Result<Void>`，成功消息为“删除成功”。只有题目创建者或管理员可以删除；服务端从登录态确定操作者，忽略客户端声称的身份。

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

## service-question 函数题

### 解析 LeetCode 题目

```http
GET /api/question/function/leetcode?title=string-to-integer-atoi
Authorization: Bearer <token>
```

`title` 使用 LeetCode URL 中的题目标识，例如：

```text
https://leetcode.cn/problems/string-to-integer-atoi/
                                    ^^^^^^^^^^^^^^^^^^^^^^
```

返回：`Result<FunctionParseVo>`，包含题目文本、难度、标签、Java 类名、方法名、参数配置、返回类型和公开样例。Java 方法修饰符不会进入返回类型；示例输出兼容 `.example-block` 和旧版 `<pre>` 题面格式。

### 创建函数题

```http
POST /api/question/function
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`FunctionDto`

```json
{
  "title": "回文数",
  "description": "给定一个整数，判断它是否为回文数。",
  "difficulty": 1,
  "timeLimit": 2000,
  "memoryLimit": 256,
  "className": "Solution",
  "methodName": "isPalindrome",
  "parameterConfig": "[{\"type\":\"int\",\"name\":\"x\"}]",
  "outputType": "boolean",
  "samples": [
    {"input": "121", "output": "true"}
  ]
}
```

返回：`Result<Long>`，`data` 为新题目 ID。函数题创建、解析、测试用例生成和批量测试用例管理属于管理员操作；题目创建者 ID 始终由服务端登录态写入，不信任请求体中的 `createUserId`。

### 批量补充函数测试用例

```http
POST /api/question/function/test-cases/batch?questionId=1
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：

```json
[
  {"input": "121", "output": "true"},
  {"input": "-121", "output": "false"}
]
```

说明：

- 新增记录是隐藏测试用例，默认 `isSample=0`、`isHidden=1`。
- 后端根据函数参数和返回类型标准化输入输出，并自动接续执行顺序。
- 同一道题的输入输出哈希唯一，重复提交返回“测试用例已存在”。
- 接口不会修改题目的公开样例字段。
- 该接口要求管理员权限，避免普通用户向任意题目注入隐藏测试数据。

返回：`Result<Integer>`，`data` 为本次新增数量。

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
- 后端根据题目类型自动选择 ACM 或函数调试流程。
- 函数模式当前只支持 Java，会读取函数配置并生成 `Main.java`。
- 函数模式会将本次调试用例一次编译、逐个运行，避免每个用例重复编译。

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

返回：`Result<Void>`。仅当前登录用户自己的提交记录可以删除，避免通过枚举提交记录 ID 删除他人记录。

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

测试点管理接口由管理员使用，测试数据不接受普通用户直接维护。

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
