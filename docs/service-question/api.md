# service-question 题目、函数题与判题接口

本文档整理题目服务的题目、函数题、提交判题和测试点接口。统一鉴权和响应约定见 [`../backend-controller-api.md`](../backend-controller-api.md)。

基础路径：`/api/question`（服务端口 `8084`）

### 错误码

业务失败的 HTTP 状态码保持 200，客户端按 `Result.code !== 200` 分支；七位错误码规则（`类别(3位)×10000 + 服务号(2位)×100 + 序号(2位)`，本服务服务号为 `02`，形如 `4040201`）与通用映射见 [`../backend-controller-api.md`](../backend-controller-api.md) 的「错误码与业务异常」一节。本服务专属语义收录在 `service-question` 的 `exception/QuestionErrorCode.java`；未登录、无权限、仅管理员、参数、下游不可用等跨服务语义复用 `CommonErrorCode`（服务号 00）。只有 `BizException` 的文案面向用户原样返回，其余异常一律换成通用中文文案。下文各接口括注的 code 均为枚举实际值。

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

- 服务读取 HTML 内容并调用解析逻辑生成 `Question`（`HtmlService.Luogu`，按标题+内容加 Redisson 分布式锁防止重复提交同一题）。
- 题目落库时 `aiStatue=pending`、`createUserId` 取登录态；AI 测试用例生成请求（`EventTypes.AI_TESTCASE_REQUEST`）经 OutboxPro 与插入同事务登记，投递到 `MqContexts.Ai_EXCHANGE` / `Ai_TESTCASE_ROUTING_KEY`，生成结果由本服务的 `question.testcace.routing` 消费者回写入 `test_case`。
- 适合后台导入题目，不适合普通用户频繁调用。

返回：`Result<Question>`。

### 新增题目

```http
POST /api/question/addquestion
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`InsertQuestionDto`（字段即下表；`difficulty`、`source` 不在该 DTO 中，创建时难度取库默认值）

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

说明：

- 需要登录（`UserContext.getUserId()` 为空时报「登录状态已失效，请重新登录」，`4010001`），创建者 ID 与来源由服务端登录态写入。
- 手工新增的题目初始 `status=3`（私密），仅创建者与管理员可通过详情接口看到；`aiStatue` 直接置 `success`（不触发 AI 测试点生成）；`contentHash` 为 `description` 的 MD5。
- 同一创建者的 `create_user_id + content_hash` 建有唯一索引，重复正文会插入失败。

### 查询题目详情

```http
GET /api/question/getquestionbyid?questionId=1
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `questionId` | `Long` | 是 | 题目 ID |

返回：`Result<QuestionVo>`。`QuestionVo` 在题目字段之外附带 `functionConfig`（仅函数题非空，含 `className`、`methodName`、`parameterConfig`、`outputType`）。

可见性规则（`QuestionService.getQuestionById`）：

- 题目不存在报「题目不存在或已被删除」（`4040201`）；`status=0` 报「该题目已下架，暂时无法访问」（`4090201`）；`status=2` 报「该题目正在审核中，暂时无法访问」（`4090202`）。
- `status=3`（私密）仅创建者本人或管理员（roleId=2，经 `UserFeignClient` 回查）可见，否则报「该内容当前不可访问」（`4030003`）。

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

鉴权：仅题目创建者本人或管理员（roleId=2，经 `UserFeignClient` 回查）可修改，否则报「没有权限执行该操作」（`4030001`）；题目不存在报「题目不存在或已被删除」（`4040201`）。

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

返回：`Result<Void>`，成功消息为“删除成功”。服务端从登录态确定操作者（经 `UserFeignClient` 回查用户，忽略客户端声称的身份）。注意：`QuestionService.deleteQuestion` 的资格判断实际写法是「回查到的用户等于当前登录用户 或 roleId=2」，而回查本身就是按当前登录用户发起的，条件恒成立——即当前实现下**任何登录用户都可删除任意题目**，与“仅创建者或管理员”的设计意图不符，属已知偏差（代码待修复前以本段描述为准）。

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
| `difficulty` | `String` | 否 | 难度过滤，支持逗号分隔多值（如 `1,2`，各值之间为 OR 条件） |
| `status` | `Integer` | 否 | 状态过滤 |
| `title` | `String` | 否 | 标题关键字 |
| `type` | `String` | 否 | 题目类型过滤：`ACM` / `FUNCTION` |

返回：`Result<CursorPageResult<ReturnQuestionDto>>`

`CursorPageResult` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `records` | `List<T>` | 当前页数据 |
| `nextCursor` | `Long` | 下一页游标 |
| `hasNext` | `Boolean` | 是否还有下一页 |
| `total` | `Long` | 保留字段，题目/提交游标查询均未填充（恒为 `null`） |

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

### 查询题目总数

```http
GET /api/question/total
Authorization: Bearer <token>
```

返回：`Result<Long>`，当前题库题目总数。

### 标题/标签模糊搜索

```http
GET /api/question/likeserach?likeKey=dp
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `likeKey` | `String` | 是 | 模糊匹配关键字 |

返回：`Result<List<QuestionVo>>`。

## service-question 函数题

函数题控制器 `FunctionQuestionParseController` 类级标注 `@RequireAdmin`：本节全部接口均要求管理员（roleId=2）或 root（roleId=0）身份，由 `service-common` 的 `AdminAuthAspect` 统一拦截。

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

返回：`Result<Long>`，`data` 为新题目 ID。函数题创建、解析、测试用例生成和批量测试用例管理属于管理员操作；题目创建者 ID 始终由服务端登录态写入，不信任请求体中的 `createUserId`。函数题创建即 `status=1`（上架），`timeLimit`/`memoryLimit`/`difficulty` 缺省时分别为 2000/256/1；`title`、`description` 必填；同一创建者正文哈希重复时报「该题目已存在，请勿重复导入」（`4090203`）。首批 `samples` 以 `isSample=1`、`isHidden=0` 入库 `function_test_case`，并取第一条回填题目样例。

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
- 同一道题的输入输出哈希唯一，重复提交返回「相同输入与输出的测试用例已存在，请勿重复添加」（`4090204`）。
- 接口不会修改题目的公开样例字段。
- 该接口要求管理员权限，避免普通用户向任意题目注入隐藏测试数据。

返回：`Result<Integer>`，`data` 为本次新增数量。

### 随机生成函数测试用例（异步任务）

```http
POST /api/question/function/test-cases/generate
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`FunctionTestCaseGenerateRequest`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `questionId` | `Long` | 是 | 目标函数题 ID（须为 FUNCTION 类型） |
| `standardAnswer` | `String` | 是 | 标准答案 Java 代码，长度上限 100000 字符 |
| `count` | `Integer` | 否 | 生成数量，默认 20，范围 1-100 |
| `seed` | `Long` | 否 | 随机输入种子，缺省用 `System.nanoTime()` |
| `language` | `String` | 否 | 目前仅支持 `java`，传其他值报错 |

返回：`Result<FunctionTestCaseGenerationTaskVo>`（`taskId`、`questionId`、`userId`、`status`、`requestedCount`、`generatedCount`、`seed`、`errorMessage`、`createTime`、`finishTime`），`status` 为 `PENDING/SUCCESS/FAILED`。

说明：机制是本地按函数参数配置生成随机输入，把标准答案作为调试提交经 `judge.exchange` / `judge.debug.routing` 送进判题沙箱执行，判题回执（`question.debug.routing`）将各用例的实际输出作为期望输出批量入库；并非由大模型直接生成。接口立即返回任务 ID，任务状态存 Redis（key 前缀 `function_case_generation_task:`），TTL 30 分钟。查询进度使用下面的状态接口，仅任务发起者本人可查。

### 查询函数测试用例生成状态

```http
GET /api/question/function/test-cases/generate/status?taskId=<taskId>
Authorization: Bearer <token>
```

返回：`Result<FunctionTestCaseGenerationTaskVo>`。

### 解析 LeetCode 题目并生成判题产物（异步任务）

```http
POST /api/question/function/leetcode/artifacts/generate?titleSlug=string-to-integer-atoi
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `titleSlug` | `String` | 是 | LeetCode 题目标识 |

返回：`Result<LeetCodeArtifactTaskVo>`，`data` 为异步任务信息（`taskId`、`titleSlug`、`questionId`、`userId`、`status`、`generatedCount`、`seed`、`artifactDirectory`、`errorMessage`、`createTime`、`finishTime`）。`status` 流转：`PENDING → FETCHING → GENERATING → VALIDATING → SAVING → SUCCESS`，任一步失败置 `MANUAL_REVIEW` 并记录 `errorMessage`。

说明：产物（generator / 标准答案源码、`question.json`、`generated-cases.txt`、`artifacts.txt`、`status.json` 等）统一保存到 `data/function-artifacts/<taskId>/`（配置项 `codewise.function-artifact.root`），验收一律经过 Docker 沙箱（镜像 `codewise.function-artifact.docker-image`，默认 `codewise-java-judge:17`，容器无网络、限时 30s），不在宿主机直接运行 AI 生成代码；验收通过后在事务内创建函数题并批量入库测试用例。任务列表在内存 Map 中维护，重启后可从各任务目录的 `status.json` 恢复读取。

### 查询 LeetCode 产物生成状态

```http
GET /api/question/function/leetcode/artifacts/status?taskId=<taskId>
Authorization: Bearer <token>
```
返回：`Result<LeetCodeArtifactTaskVo>`。

### 批量导入函数题（本地仓库/外部题库）

```http
POST /api/question/function/batch-import
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`FunctionBatchImportRequest`

```json
{
  "caseCount": 20,
  "seed": 20261019,
  "allowDescriptionGeneration": true,
  "defaultSource": "原创改编",
  "items": [
    {
      "title": "在二叉树中增加一行",
      "difficulty": 2,
      "tags": "二叉树,广度优先搜索",
      "methodName": "addOneRow",
      "className": "Solution",
      "parameterConfig": "[{\"type\":\"TreeNode\",\"name\":\"root\"},{\"type\":\"int\",\"name\":\"val\"},{\"type\":\"int\",\"name\":\"depth\"}]",
      "outputType": "TreeNode",
      "solutionCode": "class Solution { public TreeNode addOneRow(TreeNode root, int val, int depth) { ... } }",
      "referenceCode": "…可选的参考实现/对拍代码，仅用于题面重写…",
      "notes": "…可选的作者笔记，仅用于题面重写…"
    }
  ]
}
```

字段说明（`items` 每项）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `title` | `String` | 是 | 题目标题 |
| `description` | `String` | 否 | 题面正文；为空且 `allowDescriptionGeneration=true` 时由 service-ai 依据解法源码原创重写 |
| `inputDesc` / `outputDesc` / `hint` | `String` | 否 | 为空时采用重写结果 |
| `difficulty` | `Integer` | 否 | 1/2/3；为空且重写题面时采用模型建议，仍为空则按 1 |
| `tags` | `String` | 否 | 逗号分隔；非空时不覆盖 |
| `source` | `String` | 否 | 来源标注，缺省取请求级 `defaultSource`，再缺省「原创改编」 |
| `timeLimit` / `memoryLimit` | `Integer` | 否 | 缺省 2000 / 256 |
| `className` / `methodName` | `String` | 方法名必填 | 提交类名缺省 `Solution`；判题代码生成依赖这对值 |
| `parameterConfig` | `String` | 是 | 参数类型与名称数组；类型限于 `int` / `long` / `String` / `int[]` / `String[]` / `int[][]` / `ListNode` / `TreeNode` |
| `outputType` | `String` | 是 | 返回值类型 |
| `solutionCode` | `String` | 是 | 标准答案（须为**已验证过**的解法）：既用于题面重写，也作为判题产出隐藏用例的标准答案，沙箱内要求能编译出 `class Solution` |
| `referenceCode` / `notes` | `String` | 否 | 参考实现与作者笔记，只参与题面重写，不参与判题 |
| `caseCount` / `seed` | `Integer`/`Long` | 否 | 覆盖请求级取值 |

返回：`Result<FunctionBatchImportTaskVo>`，`data` 为异步任务快照（`taskId`、`status`、`total`、`submitted`、`failed`、`items[]`，每条含 `title`、`questionId`、`generationTaskId`、`descriptionGenerated`、`status`、`errorMessage`）。

说明：

- 控制器类级 `@RequireAdmin`：仅管理员（roleId=2）或 root（roleId=0）可调用。
- 单次最多 50 条；条目必填项缺失时整批请求被拒（提示第几条）。
- 处理是**异步且逐条隔离**的：任务状态 `RUNNING → SUCCESS/PARTIAL/FAILED`，单条失败只标记该条并把原因写进 `errorMessage`，不影响其他条目。
- 逐条流程：题面（缺省时重写）→ 建题与函数配置（`samples` 留空，`status=3` 私密）→ 以本条解法为标准答案提交隐藏用例生成（复用随机测试生成链路，见上文）。
- 题面重写会顺带返回**参数取值域建议**（`parameterRanges`），服务端按参数名合并进随机生成配置（只影响采样，不改落库配置），保证题面写的约束（正整数、0~9 的数字、只含 `1-9` 与 `#` 的字符串等）与实际用例一致；条目里显式给出的 `parameterRanges` 优先。
- 条目进入 `GENERATING` 表示题目已建、用例仍在异步生成：用 `generationTaskId` 查既有生成任务接口，成功后调「提升样例」接口回填公开样例。
- 身份显式传递：任务在线程池里执行，`userId` 在创建任务时捕获并在工作线程重新写入，避免写出空的 `create_user_id`。

### 查询批量导入任务状态

```http
GET /api/question/function/batch-import/status?taskId=<taskId>
Authorization: Bearer <token>
```

返回：`Result<FunctionBatchImportTaskVo>`。任务快照存 Redis（键前缀 `function_batch_import_task:`，TTL 6 小时），仅管理员可查。

### 提升隐藏用例为公开样例

```http
POST /api/question/function/test-cases/sample/{questionId}?count=1&publish=true
Authorization: Bearer <token>
```

说明：把该题**最早的**若干条隐藏用例（`is_sample=0`）提升为公开样例（`is_sample=1`、`is_hidden=0`），并把第一条回填到题目的 `sample_input`/`sample_output`。判题读取该题全部用例、不按 `is_hidden` 过滤，所以提升不会减少判题覆盖。`count` 缺省 1，范围 1~10；题目不是函数题报 `4000206`，已无隐藏用例可提升报 `4090207`。

`publish`（缺省 `false`）为 `true` 时同时把题目置为上架（`status=1`）。批量导入的题目建库时是私密（`status=3`），调用方在隐藏用例生成成功后带上 `publish=true` 完成上架——这样「用例生成失败」的题不会以 0 个测试点的形态暴露给用户。

返回：`Result<Integer>`，`data` 为本次提升的用例条数。

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
  "questionId": 1,
  "questionTitle": "A+B Problem",
  "submitScene": "NORMAL"
}
```

字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | `String` | 是 | 提交代码 |
| `language` | `String` | 是 | 语言标识 |
| `questionId` | `Long` | 是 | 题目 ID，为空报「题目不存在或已被删除」（`4040201`） |
| `questionTitle` | `String` | 否 | 题目标题，冗余写入提交记录 |
| `submitScene` | `String` | 是 | 提交场景：`NORMAL` / `REVIEW` / `PLAN` / `GROUP` |
| `groupId` | `Long` | 小组提交必填 | 小组 id；`submitScene=GROUP` 时必填，其他场景不得传 |

返回：`Result<Long>`，`data` 为 `submitRecordId`。

用法说明：

- 接口只返回提交记录 ID。
- `submitScene` 缺失或为空白时报业务错误「提交类型不能为空，请刷新后重试」（`4000207`），不在枚举内的值报「不支持该提交类型，请刷新后重试」（`4000208`）（服务端先校验再进入归一化，不会再因缺字段抛 NPE 得到 500）。
- `userId` 取自 `UserContext`；提交记录以 `judge_status=pending` 落库，判题请求事件（`EventTypes.JUDGE_SUBMIT_REQUEST`，载荷仅 `submitRecordId`）经 OutboxPro 与插入同事务登记，由 Relay 投递到 `MqContexts.JUDGE_EXCHANGE` / `JUDGE_ROUTING_KEY`。
- 判题结果异步处理，前端可通过提交记录接口或 WebSocket 获取后续状态（异步链路见文末“提交与判题异步链路”一节）。

**小组提交（`submitScene=GROUP`）**：

```json
{
  "code": "print(input())",
  "language": "python",
  "questionId": 1,
  "questionTitle": "A+B Problem",
  "submitScene": "GROUP",
  "groupId": 88
}
```

- `groupId` 为空报 `4000238`，非正数报 `4000239`；非 `GROUP` 场景却传了 `groupId` 报 `4000240`，避免 groupId 被静默忽略。
- 无论题目是否公开，都必须同时满足「题目在该小组题库中」和「当前用户是该组 APPROVED 成员」：非组员报 `4030204`，题目不在该组报 `4040209`；小组提交额外写入 `group_submit_record` 索引。
- 提交记录、小组索引与判题 Outbox 事件在同一事务内写入，任一步失败整体回滚。

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

- `tests` 为临时调试用例，不一定写入正式测试点；`DebugDto` 另有可选字段 `includeQuestionSample`（是否附带题目样例参与调试）。
- `userId` 由服务端登录态写入，`tests` 每项为 `{input, output}`。
- 后端根据题目类型自动选择 ACM 或函数调试流程。
- 函数模式当前只支持 Java，会读取函数配置并生成 `Main.java`。
- 函数模式会将本次调试用例一次编译、逐个运行，避免每个用例重复编译。

返回：`Result<String>`，`data` 为本次调试任务的 UUID。调试请求经 OutboxPro（`EventTypes.JUDGE_DEBUG_REQUEST`，与 Redis 任务写入同事务）投递到 `MqContexts.JUDGE_EXCHANGE` / `JUDGE_DEBUG_ROUTING_KEY`；判题完成后结果由本服务 `question.debug.routing` 消费者接收，并通过 WebSocket 通道 `debug-judge-result`（`WebsocketContexts.DEBUG_JUDGE_RESULT`）推送给前端。

### 根据提交记录 ID 查询

```http
GET /api/question/getsubmitrecordbyid?submitRecordId=1
Authorization: Bearer <token>
```

返回：`Result<SubmitRecord>`。仅查询当前登录用户自己的提交记录（查询条件带 `user_id`），查不到时报「提交记录不存在或已被删除」（`4040203`），不能通过枚举 ID 读取他人代码。

### 查询某题提交记录

```http
GET /api/question/getsubmitrecordsbyquestionid?questionId=1
Authorization: Bearer <token>
```

返回：`Result<List<SubmitRecord>>`。只返回当前登录用户在该题的提交，按 `submitTime` 倒序。

### 查询当前用户提交记录

```http
GET /api/question/getsubmitrecordsbyuserid?pageSize=20&lastId=100
Authorization: Bearer <token>
```

说明：不需要传 `userId`，服务从登录态读取当前用户。按提交记录 ID **倒序**游标分页（`id < lastId`），第一页不传 `lastId`，后续页传上一页的 `nextCursor`。

返回：`Result<CursorPageResult<SubmitRecord>>`（字段见游标分页查询题目一节）。

### 查询小组提交记录

```http
GET /api/question/group/88/submit-records?lastId=100&pageSize=20
Authorization: Bearer <token>
```

说明：

- 仅该小组的 APPROVED 成员可查看，非组员报「您不是该小组成员，无法查看小组提交记录」（`4030205`）；`groupId` 为空/非正数报 `4000238` / `4000239`。
- 不按题目过滤，返回该小组全部提交记录；按 `group_submit_record_id` **倒序**（自增主键与提交时间同序）游标分页，第一页不传 `lastId`，后续页传上一页的 `nextCursor`。
- `pageSize` 为空/非正数取 20，超过 50 截断。
- 服务端每页只做一次 `submit_record` 批量查询和一次用户信息批量查询，不逐条调用；用户已注销时昵称/头像返回 `null`。
- 提交记录已不存在的孤儿小组索引会跳过输出，但游标仍按原索引推进，不会重复或卡死。

返回：`Result<CursorPageResult<MiniGroupSubmitRecordVo>>`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `groupSubmitRecordId` | `Long` | 小组提交记录 id |
| `submitRecordId` | `Long` | 提交记录 id |
| `questionId` | `Long` | 题目 id |
| `questionTitle` | `String` | 题目标题 |
| `nickName` | `String` | 昵称，缺失时回退用户名；用户已删除时为 `null` |
| `avatarUrl` | `String` | 头像 |
| `status` | `String` | 判题状态 |

### 批量查询提交记录

```http
POST /api/question/getsubmitrecordsbyids
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：

```json
[1, 2, 3]
```

约束：ID 列表去重后最多 10 个、必须为正整数；只返回当前登录用户自己的记录，按 `submitTime` 倒序。

返回：`Result<List<SubmitRecord>>`。

### 删除提交记录

```http
DELETE /api/question/deletesubmitrecord?submitRecordId=1
Authorization: Bearer <token>
```

返回：`Result<Void>`。仅当前登录用户自己的提交记录可以删除，避免通过枚举提交记录 ID 删除他人记录。

## service-question 测试点管理

测试点控制器 `TestCaseController` 类级标注 `@RequireAdmin`（管理员或 root 才能调用）；服务层另按题目维度校验“创建者或 roleId=2 管理员”。

### 查询每题测试点数量

```http
GET /api/question/getallquestion
Authorization: Bearer <token>
```

说明：返回 `List<Map<String,Object>>`，前面每项为 `{question_id, count}`（每题测试点数量，按 question_id 升序），列表末尾额外附加一条汇总 `{total: 题目数, questions: [题目ID升序列表]}`。

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

新增时 `caseId` 由服务端置空、`createUserId` 取登录态用户。测试点管理接口由管理员使用，测试数据不接受普通用户直接维护。

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

说明：DTO 字段为 `null` 时保留原值（逐字段判空覆盖），全部字段可选。

返回：`Result<TestCase>`。

### 删除测试点

```http
DELETE /api/question/deletetestcase?caseId=1
Authorization: Bearer <token>
```

返回：`Result<Void>`。

## service-question 批量详情与内部接口

### 批量查询题目详情（Agent 专用）

```http
POST /api/question/agent/detail
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：`AgentQuestionDetailDto`

```json
{ "questionIds": [1, 2, 3] }
```

说明：供 codewise-agent 在收藏夹瘦身列表之上按需拉详情；需登录，`questionIds` 去重后 1-10 个，超出报「单次最多查询 10 道题目详情」（`4000205`）。逐题独立判定可见性（与单题详情规则一致：私密题仅创建者与管理员可见，下架/审核中一律不可见；角色仅在批次命中他人私密题时回查一次），不因个别题目失败而整体报错。

返回：`Result<List<AgentQuestionDetailVo>>`，每项含 `questionId`、`state`（`ok`/`not_found`/`invisible`）、`message`、`question`（仅 `state=ok` 时有值，结构同 `QuestionVo`）。

### 服务间题目信息接口（QuestionFegin）

供其他服务经 OpenFeign 调用（`service-api` 的 `QuestionFeignClient`），不做题目可见性过滤，前端不应直接使用：

| 方法 路径 | 用途 | 返回 |
| --- | --- | --- |
| `GET /api/question/info/{questionId}` | 按 ID 查单个完整题目 | `Result<QuestionDto>` |
| `POST /api/question/info/favoritequestions` | 按 ID 列表批量查完整题目（收藏快照等），不存在的 ID 跳过 | `Result<List<QuestionDto>>` |
| `POST /api/question/info/briefquestions` | 按 ID 列表批量查瘦身信息（不含题干/样例等大字段，保留 `status`/`createUserId` 供调用方自行过滤） | `Result<List<QuestionBriefDto>>` |

批量接口请求体均为题目 ID 数组（空列表返回空结果）。

### 内部判题上下文

```http
GET /api/question/internal/judge-context/{judgeRecordId}
Authorization: Bearer <token>（或仅内部 Token 的服务间调用）
```

用途：AI 建议消息瘦身后只携带 `judgeRecordId`，`service-ai` 经 `QuestionFeignClient#getJudgeContext` 回拉代码、日志、输入输出、题目描述等大字段，组装 `JudgeContextDto`（`judgeRecordId`、`submitRecordId`、`questionId`、`language`、`judgeStatus`、`code`、`log`、`inputData`、`expectedOutput`、`userOutput`、`questionContent`）。

鉴权（防 IDOR）：无用户上下文（`UserContext.getUserId()==null`，即 MQ 消费线程发起、仅携带 `X-Internal-Token` 的内部调用）放行；携带用户身份时必须是管理员（roleId=2），否则报「该功能仅限管理员使用」（`4030002`）。关联记录缺失时返回 `Result.error` 文本，不抛堆栈。

## 提交与判题异步链路

1. `POST /api/question/judge`：`submit_record` 落库（`judge_status=pending`，`submitScene` 归一化为 `NORMAL/REVIEW/PLAN`）+ `EventTypes.JUDGE_SUBMIT_REQUEST` 经 OutboxPro 同事务登记，事件路由在 `OutboxEventRouteConfig` 声明，投递到 `MqContexts.JUDGE_EXCHANGE` / `JUDGE_ROUTING_KEY`（载荷仅 `submitRecordId`）。
2. `service-judge` 领取时先 CAS `pending→judging`，在 Docker 容器池内编译运行并写 `judge_record`，随后将结果（载荷 `judgeRecordId`）经其自身 Outbox 路由发回 `question.exchange` / `question.submit.record.routing`。
3. 本服务 `MQ/Mq` 监听队列 `question.queue`（`MqContexts.Question_QUEUE_NAME`），按路由键分发给 `MessageHandler` 实现：

   | 路由键（MqContexts 常量） | 处理器 | 职责 |
   | --- | --- | --- |
   | `question.submit.record.routing`（`QUESTION_SUBMIT_RECORD_ROUTING_KEY`） | `SubmitRecordHandel` | CAS 收尾 `judging→success`、题目计数、REVIEW/PLAN 事件 Outbox 转发、WebSocket 推送 |
   | `question.debug.routing`（`QUESTION_DEBUG_ROUTING_KEY`） | `DebugHandle` | 调试结果/随机用例生成任务收尾，经 `debug-judge-result` 推送 |
   | `question.testcace.routing`（`Question_TESTCASE_ROUTING_KEY`，注意常量值拼写为 testcace） | `TestCaseHandle` | AI 生成的测试用例按输入+期望输出 MD5 去重入库，收尾 `ai_statue=success` |
   | `question.delete.queue.routing`（`QUESTION_DELETE_QUESTION_ROUTING_KEY`） | `DeleteQuestion` | 将题目 `ai_statue` 置 `failure` |

4. `SubmitRecordHandel` 可靠性：载荷信封/裸 `Long` 双读（`EnvelopeCodec.unwrap`）；CAS 幂等（重复投递跳过）；事务提交后才 ACK；失败按消息体 MD5 在 Redis 计数（`question:judge:retry-count`），未 3 次 requeue 重投，超限留存 `question:judge:failed` 后丢弃。`REVIEW` 场景经 `EventTypes.REVIEW_JUDGE_RECORD`（→ `REVIEW_EXCHANGE` / `REVIEW_JUDGE_RECORD_ROUTING_KEY`）、`PLAN` 场景经 `EventTypes.PLAN_JUDGE_RECORD`（→ `REVIEW_EXCHANGE` / `PLAN_JUDGE_RECORD_ROUTING_KEY`）转发给 `service-review`。WebSocket 推送为 ACK 后尽力而为：`message.exchange` / `WEBSOCKET_ROUTING_KEY`，通道 `judge-result`，`code/log/expectedOutput/actual` 大字段截断至 16KB。
5. 兜底：定时任务 `SubmitTask` 每 5 分钟扫描 `judge_status=pending` 且创建超 5 分钟的记录，重发判题请求到 `JUDGE_EXCHANGE` / `JUDGE_ROUTING_KEY`；`QuestionAiStatueTask` 每 5 分钟扫描 `ai_statue` 为 `pending`（超时）/`failure` 的题目重试 AI 用例生成（测试点已 ≥5 个则直接置 `success`）。

判题 DB 为准，WebSocket 推送为尽力而为；前端以提交记录查询接口轮询兜底。

## 数据表

本服务独占 `codewise_question` 库（建表脚本见 `service-question/src/main/resources/sql/`）：

| 表 | 说明 | 关键字段 |
| --- | --- | --- |
| `question` | 题目 | `question_id`(自增)、`question_type`(ACM/FUNCTION)、`status`(0 下架/1 正常/2 审核/3 私密)、`ai_statue`、`content_hash`、`total_submit`/`total_ac`/`pass_rate`；唯一索引 `create_user_id+content_hash` |
| `test_case` | ACM 测试点 | `case_id`、`question_id`、`input_data`/`expected_output`、`is_sample`/`is_hidden`、`sort_order`/`score_weight`、单用例 `time_limit`/`memory_limit` |
| `function_config` | 函数题配置 | `question_id`(唯一)、`class_name`、`method_name`、`parameter_config`(JSON)、`output_type` |
| `function_test_case` | 函数题测试用例 | `question_id`、`input_data`/`expected_output`、生成列 `case_hash`(SHA-256)，唯一索引 `question_id+case_hash` |
| `submit_record` | 提交记录 | `submit_record_id`、`question_id`、`user_id`、`submit_status`(AC/WA/TLE/RE/CE/OLE/MLE/PE)、`judge_status`(pending/judging/success)、`submit_scene`、`language` |
| `judge_record` | 判题记录（判题机写入，本服务读取） | `judge_record_id`、`submit_record_id`、`test_case_id`、`submit_status`、`fail_index`、`test_total`、`code`/`input_data`/`expected_output`/`user_output`/`log`/`error_msg`、`time_used`/`memory_used` |

Outbox 事件表由 OutboxPro 管理（`outboxpro.enabled: true`，`outboxpro.producer.poll-interval: 1000` 为必填纯毫秒值）。

## 配置与环境

- 服务端口 `8084`；Nacos 配置 `service-question.yaml`（`NACOS_ADDR`）。
- `codewise.internal-token` 来自环境变量 `CODEWISE_INTERNAL_TOKEN`（无默认值，缺失启动失败），网关/内部调用鉴权用。
- 函数判题产物目录 `codewise.function-artifact.root`（默认 `./data/function-artifacts`，不进版本库），沙箱镜像 `JUDGE_IMAGE`（默认 `codewise-java-judge:17`），单用例超时 `timeout-seconds: 30`。
- RabbitMQ 手动 ACK（`spring.rabbitmq.listener.simple.acknowledge-mode: manual`），消费者在代码内手动确认，见上文可靠性说明。
- MyBatis XML 位于大写目录 `resources/Mapper/`，`mapper-locations` 已显式声明大小写双路径。
- HTML 导入文件大小上限 20MB（与 nginx `client_max_body_size` 对齐）。
- 接口治理：`api.governance` 启用 Redis 滑动窗口限流（`@RateLimit`），超限时 HTTP 仍为 200，返回 `code=4290001`、提示「题目操作过于频繁，请稍后重试」，管理端点前缀 `/api/question/governance`（访问需管理员）。
