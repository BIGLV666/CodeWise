# service-judge 模块说明

`service-judge` 是 CodeWise 的异步判题执行服务。它不直接接收用户提交请求，而是通过 RabbitMQ 消费 `service-question` 发布的判题或调试任务，在 Docker 容器中编译、运行代码，并把判题结果回调给题目服务。

容器池以受限、非 root 容器执行不可信代码：根文件系统只读，只有 `/workspace` 与 `/tmp` 使用容量受限的临时文件系统；容器禁用网络、移除全部 capabilities 并启用 `no-new-privileges`，同时限制 CPU、内存和 PID 数。执行超时或异常的容器不会回收到池中，而是销毁重建以清理可能遗留的进程树。

## 1. 包结构

```text
service-judge/src/main/java/org/example/servicejudge/
|-- config/                         # Docker、Web 配置（Feign 头透传由 service-common 自动配置提供）
|-- controller/                     # 管理员 HTTP 接口
|   |-- DockerController.java
|   `-- FailureSubmitController.java
|-- Dto/                            # 模块内部 DTO；保留仓库现有大小写
|-- entry/                          # MyBatis-Plus 数据库实体
|-- enums/                          # 题型、失败提交状态枚举
|-- functionsService/               # 函数题 Java 包装代码生成；保留现有包名
|-- interfaces/                     # 判题执行抽象
|-- judge/                          # Docker 编译、执行、判定核心
|   |-- JudgeService.java
|   |-- JudgeResults.java
|   |-- LanguageSpec.java
|   `-- container/                  # ContainerPoolManager 容器池、DockerExecTemplate 沙箱执行
|-- mapper/                         # MyBatis-Plus Mapper
|-- Mq/                             # 判题 MQ 消费；按仓库约定保留 `Mq` 大小写
|   |-- consumer/
|   |   |-- JudgeSubmitConsumer.java      # judge.submit.queue 消费者
|   |   |-- JudgeDebugConsumer.java       # judge.debug.queue 消费者
|   |   `-- JudgeRetryConsumer.java       # judge.retry.queue 消费者
|   `-- handler/
|       |-- JudgeSubmitHandler.java       # 初次判题
|       |-- JudgeDebugHandler.java        # 调试判题
|       |-- JudgeRetryHandler.java        # 失败重试
|       `-- JudgeDeadLetterHandler.java   # 死信登记
|-- service/
|   |-- JudgeTaskService.java             # 任务领取、Outbox 登记、事务编排
|   `-- failure/
|       `-- FailureSubmitService.java     # 管理员失败记录查询和人工重试
|-- task/
|   `-- FailureSubmitReconcileTask.java   # 超时状态补偿
|-- Util/                           # 判题结果和函数代码构建工具；保留现有大小写
`-- vo/                             # 管理接口响应模型
```

### 分层规则

- `Mq/consumer` 只放 RabbitMQ 监听与 ACK/NACK 入口，`Mq/handler` 放对应业务处理器。
- `service` 只放可复用业务编排；当前失败提交管理位于 `service/failure`。
- `judge` 只负责容器池、编译、执行和判定，不处理 MQ ACK/NACK。
- `mapper` 只访问判题服务使用的 `codewise_question` 库（与 service-question 共库，只读写判题相关表），不能跨其他业务库查询。
- `task` 只放定时补偿任务。
- `controller` 只负责参数接收和 `Result<T>` 包装。
- 新增判题场景时，在 `MqContexts`/`MqConfig` 声明队列与 routing key，并新增 `Judge<Scene>Consumer` + `Judge<Scene>Handler`；不要在业务类中硬编码队列名。

## 2. 核心类职责

| 类 | 职责 |
| --- | --- |
| `JudgeService` | 管理 Docker 容器池；编译、批量执行测试点并生成 `JudgeRecord` |
| `JudgeSubmitConsumer` | 监听 `judge.submit.queue`，领取任务（`pending -> judging` CAS）并交给 `JudgeSubmitHandler` |
| `JudgeDebugConsumer` | 监听 `judge.debug.queue`，处理调试任务 |
| `JudgeRetryConsumer` | 监听 `judge.retry.queue`，处理人工重试任务 |
| `JudgeSubmitHandler` | 处理正常提交，写入判题结果，经 Outbox 回调题目服务；按原业务规则可触发 AI 建议 |
| `JudgeDebugHandler` | 从 Redis 读取调试任务，执行样例/自定义用例并回写调试结果 |
| `JudgeRetryHandler` | 原子抢占失败记录，复用已有结果或重新判题，记录错误并回调题目服务；不触发 AI 建议 |
| `JudgeDeadLetterHandler` | 消费判题死信，将提交登记到 `failure_submit` 并标记本地提交为失败 |
| `FailureSubmitService` | 管理员按状态查询失败记录，将待处理记录投递到重试 routing key |
| `FailureSubmitReconcileTask` | 每分钟修正超过五分钟仍处于重试中/失败状态的异常记录 |

## 3. 消息路由

| 场景 | Exchange | Routing key | 队列 | Consumer |
| --- | --- | --- | --- | --- |
| 正常判题 | `judge.exchange` | `judge.routing` | `judge.submit.queue` | `JudgeSubmitConsumer` |
| 调试判题 | `judge.exchange` | `judge.debug.routing` | `judge.debug.queue` | `JudgeDebugConsumer` |
| 失败重试 | `judge.exchange` | `judge.retry.routing` | `judge.retry.queue` | `JudgeRetryConsumer` |
| 判题死信 | `judge.dlx` | `judge.dead` | `judge.dead.queue` | `JudgeDeadLetterHandler` |

submit/debug/retry 队列均绑定 `judge.dlx`，消费失败 `basicNack(requeue=false)` 的消息进入死信队列。旧版 `judge.queue` 统一消费者（`Mq` + `MessageHandler` 分发）已被三个独立消费者替代并弃用；旧队列排空步骤见 `docs/maintenance/messaging-reliability.md`。结果回调经 Outbox（`event_outbox` 表 + Relay）投递到 `question.exchange / question.submit.record.routing`。

## 4. 正常判题流程

```text
service-question
  -> 事务提交 submit_record + event_outbox 登记
  -> Outbox Relay 投递 judge.exchange / judge.routing
  -> judge.submit.queue
  -> JudgeSubmitConsumer（pending -> judging CAS 领取）
  -> JudgeSubmitHandler
  -> JudgeService
  -> judge_record
  -> Outbox / question.exchange / question.submit.record.routing
  -> service-question 更新提交记录（judging -> success CAS 幂等）
```

正常判题结果为 `WA`、`RE` 或 `TLE` 时，`JudgeSubmitHandler` 保留原有 AI 建议投递逻辑。

## 5. 死信与重试流程

### 5.1 死信登记

`JudgeDeadLetterHandler.consumeDeadMessage`：

1. 接收死信中的 `submitRecordId`。
2. 插入一条 `failure_submit`，初始状态为 `PENDING(0)`，重试次数为 `0`。
3. 将判题服务本地 `submit_record.judge_status` 从 `judging` 改为 `failure`。
4. `submit_record_id` 有唯一索引，防止同一提交生成多条失败记录。

### 5.2 人工发起重试

管理员调用：

```http
POST /api/judge/failure/retry/{failureId}
```

`FailureSubmitService.retry` 只允许 `PENDING(0)` 记录投递到：

```text
judge.exchange / judge.retry.routing
```

投递动作不直接修改状态。状态抢占由消费者通过条件更新完成，避免“消息发送失败但状态已变更”。

### 5.3 重试处理

`JudgeRetryHandler.handle` 的顺序：

1. `updateStatusToRetry` 原子地把 `PENDING(0)` 或 `FAILURE(3)` 改为 `RETRYING(1)`，并将 `retry_count + 1`。
2. 查询失败记录和对应提交记录。
3. 如果提交已经成功，直接将失败记录改为 `SUCCESS(2)` 并 ACK。
4. 如果已经存在 `judge_record`，复用最新结果重新回调题目服务，不重复执行 Docker 判题。
5. 将提交从 `pending` 条件更新为 `judging`。
6. 按 ACM 或 FUNCTION 模式执行判题。
7. 校验 `judge_record` 插入行数和回填主键。
8. 回调题目服务。
9. 将失败记录改为成功、清空 `last_error`，最后 ACK。

重试流程明确不发送 AI 建议，防止补偿任务重复触发建议：

```text
JudgeRetryHandler -> question.exchange
JudgeRetryHandler -X-> ai.exchange
```

### 5.4 重试失败

任何业务异常都会：

- 将失败记录改为 `FAILURE(3)`；
- 更新 `retry_time`；
- 把异常类型、消息和堆栈摘要写入 `last_error`；
- `last_error` 最多保留 4000 个字符；
- 对当前消息执行 `basicNack(requeue=false)`。

`retry_count` 只在一次重试开始时增加，成功/失败落状态时不重复增加。

### 5.5 定时补偿

`FailureSubmitReconcileTask.reconcileFailureSubmits` 每分钟运行一次，处理超过五分钟的 `RETRYING(1)` 或 `FAILURE(3)`：

- 已存在 `judge_record`：失败记录改为成功，本地提交改为成功，清空 `last_error`。
- 不存在 `judge_record`：失败记录恢复为待处理，本地提交恢复为 `pending`，保留 `last_error`。

该任务用于处理进程崩溃、数据库已落结果但消息回调中断等不完整状态。

## 6. failure_submit 状态

| 数值 | 枚举 | 含义 |
| --- | --- | --- |
| `0` | `PENDING` | 等待管理员或补偿流程重新投递 |
| `1` | `RETRYING` | 已被某个消费者抢占，正在重试 |
| `2` | `SUCCESS` | 已完成重试或确认已有成功结果 |
| `3` | `FAILURE` | 最近一次重试失败，可在补偿后再次处理 |

关键字段：

| 字段 | 说明 |
| --- | --- |
| `submit_record_id` | 对应提交记录，具有唯一索引 |
| `retry_count` | 已开始的重试次数 |
| `retry_time` | 最近一次状态变化/重试时间 |
| `last_error` | 最近一次重试异常摘要；成功时清空 |

## 7. 新增 Mapper 方法

### `updateStatusToRetry`

条件更新 `PENDING/FAILURE -> RETRYING`，同时增加一次 `retry_count`。返回 `1` 表示抢占成功，`0` 表示记录不存在、已处理或已被其他消费者抢占。

### `updateStatus`

条件更新目标状态。更新为 `SUCCESS(2)` 时清空 `last_error`，不增加重试次数。

### `updateFailureStatus`

写入 `FAILURE(3)`、`retry_time` 和 `last_error`。用于保留最近一次失败现场。

### `reconcileStaleSubmits`

单条关联更新 SQL，同时修正 `failure_submit` 和判题服务本地 `submit_record` 的超时状态。

## 8. 管理接口

所有接口通过 Gateway 调用，并要求管理员身份。

```http
GET /api/judge/failure/{status}/list
POST /api/judge/failure/retry/{failureId}
```

状态参数使用 `0`、`1`、`2`、`3`。重试接口只接受当前状态为 `PENDING(0)` 的记录。

容器池管理接口仍位于：

```http
GET    /api/judge/containers
POST   /api/judge/containers/{language}
DELETE /api/judge/containers/{language}/{containerId}
```

## 9. 开发约定

1. MQ 消费者命名统一为 `Judge<Scene>Consumer`（`Mq/consumer`），业务处理器命名为 `Judge<Scene>Handler`（`Mq/handler`）。
2. 普通业务服务以领域分包，例如 `service/failure`。
3. 定时任务命名为 `<Domain><Action>Task`，放在 `task`。
4. 新增判题场景必须新增独立 Consumer + Handler，并复用 `MqContexts` 中的队列常量；不再使用统一分发器。
5. ACK/NACK 由消息消费者负责；同一 delivery tag 不得先 ACK 后 NACK。
6. 重投场景必须先考虑数据库幂等，不能只依赖 RabbitMQ 消息不重复。
7. 队列、交换机和 routing key 必须复用 `MqContexts`，不得硬编码。
8. 判题服务只能访问自己的数据库；跨服务结果通过 RabbitMQ 回调。
9. 修改失败重试逻辑时，至少运行 `JudgeRetryHandlerTest`。

## 10. 构建与测试

```powershell
.\mvnw.cmd -f service-common\pom.xml -DskipTests install
.\mvnw.cmd -f service-judge\pom.xml -DskipTests clean package
.\mvnw.cmd -f service-judge\pom.xml '-Dtest=JudgeRetryHandlerTest,JudgeDebugHandlerTest' test
```

完整 Spring 上下文测试依赖 Nacos、Docker、MySQL、Redis 和 RabbitMQ。缺少外部环境时优先运行无上下文的处理器单元测试。
