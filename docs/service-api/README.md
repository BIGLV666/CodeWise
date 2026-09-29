# service-api 共享契约模块

`service-api` 存放跨服务 OpenFeign 契约和共享 DTO。它不是独立运行的 Spring Boot 服务，但文档仍按模块单独归档，便于维护服务边界。

## 维护约定

- Feign 接口放在 `feign/`。
- 跨服务 DTO 放在 `dto/`。
- HTTP 统一包装 `Result<T>` 由本模块提供（注意：它在 service-api，不在 service-common）。
- 跨服务错误码契约放在 `exception/`（`ErrorCode` 接口、`CommonErrorCode`、`BizException`）。
- 具体业务实现保留在数据所属服务中。

## Result<T>（`dto/Result.java`）

字段：`code`（Integer，**默认 200**）、`message`、`data`。静态工厂：`success(data)` / `success(data, message)` / `success(message)` 均置 200；`error(message)` 置 **400**；`error(message, code)` 自定义码；`error(ErrorCode)` 与 `error(ErrorCode, args...)` 直接取枚举的码与文案（带 `%s` 占位时格式化）。

## 错误码与业务异常（`exception/`）

- `ErrorCode`：接口，`getCode()` / `getMessage()`。一个常量 = 一个稳定业务码 + 一句可直接展示的中文文案。
- `CommonErrorCode`（服务号 00）：跨服务通用语义——`PARAM_INVALID`/`PARAM_MISSING`/`BODY_UNREADABLE`/`LOGIN_REQUIRED`/`TOKEN_INVALID`/`NO_PERMISSION`/`ADMIN_ONLY`/`CONTENT_RESTRICTED`/`RESOURCE_NOT_FOUND`/`STATE_CONFLICT`/`DUPLICATE_RESOURCE`/`RATE_LIMITED`/`INTERNAL_ERROR`/`DATA_OPERATION_FAILED`/`DOWNSTREAM_UNAVAILABLE`/`MESSAGE_PARSE_FAILED`。各服务枚举**不再自建同义码**。
- `BizException`：`RuntimeException` 子类，四种构造：`(枚举)`、`(枚举, args...)`（填充 `%s`）、`(枚举, cause)`、`(枚举, cause, args...)`。cause 只进日志。
- 编码规则：`code = 类别(3位) × 10000 + 服务号(2位) × 100 + 序号(2位)`。类别取 400/401/403/404/409/429/500；服务号 00 通用、01 user、02 question、03 review、04 community、05 message、06 ai、07 judge、08 common 基础设施、09 gateway、10 python agent。示例：`4040201`（题目不存在）、`4030501`（message 侧某越权码）。
- HTTP 状态码恒为 200（网关自身失败除外），失败与否由 `Result.code !== 200` 判断——前端 `src/api/request.ts` 与 agent `gateway.ts` 都只按该约定分支，因此新增码可自由分段而不破坏客户端。

## Feign 契约（`feign/`，均不写 url，走 Nacos 负载均衡；请求头由 service-common 的 `FeignHeaderAutoConfiguration` 统一再注入身份三件套 + X-Internal-Token）

| 接口 | 目标服务 | 方法 |
|---|---|---|
| `UserFeignClient` | service-user | `getUserInfo(userId)` → `GET /api/user/info/{userId}`；`getUserList(ids)` → `POST /api/user/info/list`（批量防 N+1） |
| `QuestionFeignClient` | service-question | `getQuestionInfo` → `GET /api/question/info/{questionId}`；`getFavorites` → `POST /api/question/info/favoritequestions`；`getFavoritesBrief`（瘦身 DTO）→ `POST /api/question/info/briefquestions`；`getJudgeContext`（内部大字段，仅服务间调用）→ `GET /api/question/internal/judge-context/{judgeRecordId}` |
| `AiArtifactFeignClient` | service-ai | `generateFunctionArtifacts` → `POST /api/ai/internal/function-artifacts`，额外显式传 `X-CodeWise-Internal-Token` 请求头 |

## DTO 与枚举清单

- `dto/ai/`：`FunctionArtifactGenerateRequest/Response`、`AiAdviceWADto`、`NotificationAiAdviceDto`
- `dto/judge/`：`JudgeMqDto`、`JudgeResultDto`、`JudgeContextDto`
- `dto/notification/`：`NotificationDto` 及 Like/Review/Appeal/Checked MQ DTO
- `dto/question/`：`QuestionDto`、`QuestionBriefDto`、`TestMessage`；`dto/user/`：`UserDto`
- `dto/event/`：`EventEnvelope`（统一消息信封）+ `EventTypes`（事件类型常量，命名「领域_对象_动作」，新增事件先在此登记）
- `enums/`：`BusinessType`、`NotificationCenterType`、`ReminderType`、`WebSocketQueueName`

相关整体约定见 [`../technical-design.md`](../technical-design.md) 和 [`../project-structure.md`](../project-structure.md)。
