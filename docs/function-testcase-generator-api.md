# 函数模式随机测试生成接口

## 创建生成任务

```http
POST /api/question/function/test-cases/generate
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "questionId": 1001,
  "language": "java",
  "standardAnswer": "class Solution { public int solve(int value) { return value * 2; } }",
  "count": 20,
  "seed": 123456
}
```

- 仅题目创建者或管理员可调用，禁用账号不可调用。
- `count` 默认为 `20`，范围为 `1-100`。
- `seed` 可不传；传入相同种子可以复现同一批随机输入。
- 当前支持 `int`、`long`、`String`、`int[]`、`String[]` 参数。
- 后端随机生成输入，用标准答案计算输出，全部成功后批量写入隐藏测试用例。
- 接口返回异步任务信息，初始状态为 `PENDING`。

## 查询生成状态

```http
GET /api/question/function/test-cases/generate/status?taskId=<taskId>
Authorization: Bearer <token>
```

任务状态包括 `PENDING`、`SUCCESS`、`FAILED`。任务仅创建者本人可查询，并在 Redis 中保留 30 分钟。

默认随机范围：

- `int`：`[-1000, 1000]`
- `long`：`[-100000, 100000]`
- `String`：长度 `0-20`，内容为大小写字母和数字
- `int[]`：长度 `0-20`，元素范围 `[-100, 100]`
- `String[]`：长度 `0-10`，单个字符串长度 `0-10`

随机生成只保证参数类型正确，不保证满足题目的跨参数业务约束。例如“两数之和一定存在答案”这类约束，后续需要增加题目级生成规则。
