# service-judge 模块说明

`service-judge` 是 CodeWise 的异步判题执行服务（端口 `8086`）。它不直接接收用户提交请求：判题主链路经 RabbitMQ 到达，HTTP 面只暴露管理接口（Gateway 转发 `/api/judge/**`）。它通过 RabbitMQ 消费 `service-question` 发布的判题或调试任务，在 Docker 容器中编译、运行代码，并把判题结果回调给题目服务。

容器按语言创建并在启动时预热：Java 使用 `codewise-java-judge:17` 镜像（构建文件 `deploy/judge-base/Dockerfile`，预创建 2 个），Python 使用 `python:3.11-alpine`，C 与 C++ 共用 `gcc:13`（各预创建 1 个）。容器池以受限、非 root（uid 1000）容器执行不可信代码：内存 256MB（含 swap 等额）、CPU 配额 1 核、PID 上限 64、网络模式 `none`、丢弃全部 Linux capabilities 并启用 `no-new-privileges`，`/tmp` 为 16MB 的 tmpfs。根文件系统**不**启用只读、`/workspace` 也不是 tmpfs（新版 Moby 下 `docker cp` 对只读容器直接拒绝；`/workspace` 为 judge-base 镜像预建的 judge 属主目录，隔离由「非 root + capabilities 全清 + no-new-privileges + tmpfs /tmp」保证）。运行时限由容器内 `timeout 2s` 强制执行（`LanguageSpec.TIME_LIMIT_MS=2000`），编译等待上限 15s（`COMPILE_TIMEOUT_MS`），函数模式批量脚本时限随用例数线性扩展。借出容器最长等待 10 秒；执行超时或异常的容器被标记污染，归还时销毁重建以清理遗留进程树，不再复用。

## 1. 包结构

```text
service-judge/src/main/java/org/example/servicejudge/
|-- Advice/                         # GlobalExceptionHandler，HTTP 面全局异常处理：继承 service-common 的 AbstractGlobalExceptionHandler，只声明 basePackages；错误码见 exception/JudgeErrorCode（服务号 07）
|-- config/                         # Docker、Web、Outbox 事件路由配置（Feign 头透传由 service-common 自动配置提供）
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
| `JudgeService` | 判题门面：编排「借容器 -> 准备工作区 -> 编译 -> 执行 -> 读结果 -> 还容器」，实现单次运行、ACM 批量、函数批量、函数调试四个入口；容器管理方法委托 `ContainerPoolManager` |
| `ContainerPoolManager` | 按语言维护容器池：启动预热（Java 2 个，Python/C/C++ 各 1 个）、借出（最长等待 10 秒）、归还、污染销毁重建与管理操作前的 `checkAdmin` 二次校验 |
| `DockerExecTemplate` | 纯 Docker I/O：按沙箱资源限制创建容器、工作区打包复制与清理、脚本执行（超时/异常回调上报污染）、结果文件读取与内存统计 |
| `JudgeTaskService` | Submit/Retry 链路统一编排：CAS 领取、无事务执行 Docker 判题、`@Transactional` 落 `judge_record` 并登记 Outbox 事件（Docker 执行绝不置于数据库事务内） |
| `JudgeSubmitConsumer` | 监听 `judge.submit.queue`（手动 ACK），信封/裸格式双读解析后交给 `JudgeSubmitHandler`；业务失败按退避转 `judge.wait.queue` 延迟重试，超限死信 |
| `JudgeDebugConsumer` | 监听 `judge.debug.queue`，处理调试任务 |
| `JudgeRetryConsumer` | 监听 `judge.retry.queue`，处理人工重试任务 |
| `JudgeSubmitHandler` | 委托 `JudgeTaskService.handleSubmit` 完成领取（`pending -> judging` CAS）、判题、落库与 Outbox 回调；WA/RE/TLE 时同事务追加 AI 建议事件 |
| `JudgeDebugHandler` | 从 Redis 读取调试任务，执行样例/自定义用例，结果写回 Redis 并回调题目服务（`question.debug.routing`）；业务失败内部消化为 `SYSTEM_ERROR` 结果，不进重试链路 |
| `JudgeRetryHandler` | 原子抢占失败记录，复用已有结果或重新判题，记录错误并回调题目服务；不触发 AI 建议 |
| `JudgeDeadLetterHandler` | 消费判题死信，按消息类型分派：仅提交类死信登记 `failure_submit` 并标记本地提交失败，重试/未知类型留痕放行 |
| `FailureSubmitService` | 管理员按状态查询失败记录，将待处理记录投递到重试 routing key |
| `FailureSubmitReconcileTask` | 每分钟修正超过五分钟仍处于重试中/失败状态的异常记录 |

## 3. 消息路由

| 场景 | Exchange | Routing key | 队列 | Consumer |
| --- | --- | --- | --- | --- |
| 正常判题 | `judge.exchange` | `judge.routing` | `judge.submit.queue` | `JudgeSubmitConsumer` |
| 调试判题 | `judge.exchange` | `judge.debug.routing` | `judge.debug.queue` | `JudgeDebugConsumer` |
| 失败重试 | `judge.exchange` | `judge.retry.routing` | `judge.retry.queue` | `JudgeRetryConsumer` |
| 延迟重试等待 | 默认交换机（按队列名直投） | TTL 到期后经 `judge.exchange`/`judge.routing` 弹回提交队列 | `judge.wait.queue`（无消费者） | 无 |
| 判题死信 | `judge.dlx` | `judge.dead` | `judge.dead.queue` | `JudgeDeadLetterHandler` |

submit/debug/retry 队列均绑定 `judge.dlx`，消费失败 `basicNack(requeue=false)` 的消息进入死信队列。提交队列的业务失败不立即死信：按消息头 `x-codewise-retry-count` 指数退避（5s/10s/20s，最多 3 次）原样转投 `judge.wait.queue` 延迟重试；载荷解析失败的毒消息与重试超限的消息直接死信。调试与重试链路不做延迟重试（失败直接死信留痕）。旧版 `judge.queue` 统一消费者（`Mq` + `MessageHandler` 分发）已被三个独立消费者替代并弃用；旧队列排空步骤见 `docs/maintenance/messaging-reliability.md`。结果回调经 OutboxPro（`outboxpro_outbox` 表 + Relay，Publisher Confirm 确认投递）投递到 `question.exchange / question.submit.record.routing`。

## 4. 正常判题流程

```text
service-question
  -> 事务提交 submit_record + outboxpro_outbox 登记
  -> Outbox Relay 投递 judge.exchange / judge.routing
  -> judge.submit.queue
  -> JudgeSubmitConsumer（信封/裸载荷解析，手动 ACK）
  -> JudgeSubmitHandler -> JudgeTaskService（pending -> judging CAS 领取）
  -> JudgeService（借容器 -> 编译 -> 逐用例执行 -> 还容器）
  -> judge_record
  -> Outbox / question.exchange / question.submit.record.routing
  -> service-question 更新提交记录（judging -> success CAS 幂等）
```

正常判题结果为 `WA`、`RE` 或 `TLE` 时，`JudgeTaskService.publishResultEvents` 与结果落库同事务追加登记 `AI_ADVICE_REQUEST` 事件（经 OutboxPro 投递到 `ai.exchange / ai.wa-advice.routing`，payload 为瘦身引用 DTO）。

## 5. 死信与重试流程

### 5.1 死信登记

`JudgeDeadLetterHandler.consumeDeadMessage` 按消息类型分派：仅 `JUDGE_SUBMIT_REQUEST`（或历史裸数值格式）登记失败；`JUDGE_RETRY_REQUEST` 的死信状态已由 `JudgeRetryHandler` 落库，未知类型或无法解析的裸消息只记录日志后 ACK 放行——绝不把 failureId 当 submitRecordId 插表。提交类死信的登记流程：

1. 解析死信中的 `submitRecordId`。
2. 插入一条 `failure_submit`，初始状态为 `PENDING(0)`，重试次数为 `0`。
3. 将判题服务本地 `submit_record.judge_status` 从 `judging` 改为 `failure`。
4. `submit_record_id` 有唯一索引，防止同一提交生成多条失败记录；重复消费触发唯一键冲突时幂等放行。
5. 登记与状态更新在同一事务内，且经代理调用保证 ACK 在事务提交之后执行。

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

所有接口通过 Gateway（路由 `/api/judge/**`，服务监听 `8086`）调用，并要求管理员身份。守卫为两层：类级 `@RequireAdmin` 切面（`AdminAuthAspect` 从 `UserContext` 取用户、经 Feign 回查 roleId，管理员 `2` 或 root `0` 放行）+ 类/方法级 `@RateLimit` 限流；容器池接口在其之上还有 `ContainerPoolManager.checkAdmin` 二次校验（仅接受 roleId==2，即 root 也无法操作容器池）。

```http
GET /api/judge/failure/{status}/list     # 限流 30 次/60 秒
POST /api/judge/failure/retry/{failureId} # 限流 30 次/60 秒
```

状态参数使用 `0`、`1`、`2`、`3`。重试接口只接受当前状态为 `PENDING(0)` 的记录。

容器池管理接口仍位于（整个控制器限流 60 次/60 秒；删除只针对空闲容器，且每种语言至少保留一个）：

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

运行配置要点（`application.yaml` + 环境变量）：

- `NACOS_ADDR`（Nacos 地址）、`CODEWISE_INTERNAL_TOKEN`（注入 `codewise.internal-token`，网关内部身份头校验必需）。
- `spring.rabbitmq.listener.simple.acknowledge-mode: manual` 必须由本地 yaml 显式兜底（消费者全部手动 ACK，默认 AUTO 会双重确认导致 channel 崩溃循环）。
- `docker.host` 默认 `unix:///var/run/docker.sock`；明文 `tcp://` 地址直接拒绝启动，远程连接必须 `docker.tls-verify: true` 且配置 `docker.cert-path`。
- `codewise.judge.consumer-concurrency`（提交队列消费并发，默认 2）须与容器池容量同步上调，否则池容器闲置或排队空转。
- `outboxpro.enabled: true`，与 question 共用 `codewise_question` 库的 `outboxpro_*` 表；`outboxpro.producer.poll-interval: 1000`（纯毫秒，Boot 3.2.4 不支持 `"1000ms"` 写法）。
- Docker 部署（`deploy/docker-compose.yml`）：service-judge 挂载宿主 `/var/run/docker.sock`（DooD），`JUDGE_CONCURRENCY` 环境变量映射消费并发；`judge-base` 一次性任务负责构建 `codewise-java-judge:17`（可用 `JUDGE_IMAGE` 覆盖镜像名）。

## 函数模式批量用例的两个约定

- **每个用例文件都以换行结尾**（`cases/case-XXXXXX.txt`）：Main 用 `readLine()` 读参数，
  单参数且取值为空串（完全合法的边界用例）时，0 字节文件会让 `readLine()` 返回 `null`，
  被测代码随即 NPE，整个批量会提前中断，调用方只看到「执行结果数量不完整」。
- **沙箱按 JDK 17 编译**（镜像 `codewise-java-judge:17`，`javac` 不带 `--release`）：使用 JDK 21 API
  （`List.removeLast` / `getLast` 等）的解法会在编译阶段失败；批量导入工具的本地闸门会先按 17 编一遍，
  避免把这类题导入后才发现。
