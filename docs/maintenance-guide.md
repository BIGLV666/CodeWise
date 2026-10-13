# CodeWise 维护手册

> 基线日期：2026-09-12（对照代码逐项校验更新）
> 适用范围：CodeWise 后端微服务仓库

## 1. 系统定位

CodeWise 是面向编程学习、在线判题、错题复习和题解社区的 Spring Cloud 微服务系统。系统将普通业务请求与高延迟、不可信的代码执行流程隔离，通过 RabbitMQ 异步调度判题，通过 Redis 保存状态与高频计数，通过 WebSocket/SSE 返回实时结果。

## 2. 模块职责

| 模块 | 默认端口 | 主要职责 |
| --- | ---: | --- |
| `service-gateway` | 8082 | 路由、JWT 校验、用户上下文透传 |
| `service-user` | 8081 | 注册登录、资料、头像和用户统计 |
| `service-question` | 8084 | 题目、测试用例、提交、调试入口 |
| `service-judge` | 8086 | Docker 编译运行、结果判定 |
| `service-review` | 8097 | 错题复习、计划、收藏夹 |
| `service-community` | 8087 | 帖子、题解、评论、点赞和排行 |
| `service-message` | 8083 | 邮件、站内通知、WebSocket 推送 |
| `service-ai` | 8085 | 判题建议、SSE 追问、会话记忆 |
| `service-api` | - | Feign 接口和跨服务 DTO |
| `service-common` | - | JWT、MQ、Redis、用户上下文等公共能力 |

另有实验性模块 `service-judge-go`（Java service-judge 的 Go 重构，见其 README）：当前仅提供 HTTP 调试/判题骨架，RabbitMQ 消费默认关闭（`JUDGE_MQ_ENABLED`），函数模式与 Docker 沙箱仍由 Java `service-judge` 承载，不参与默认部署。

## 3. 外部依赖

- JDK 21、Maven Wrapper
- MySQL 8.x
- Redis
- RabbitMQ
- Nacos
- Docker Engine，判题服务必须使用
- AI 模型服务按 Nacos 中的实际配置准备

生产或共享环境不得把密码、JWT 密钥、模型密钥写入仓库。连接信息通过 Nacos 或环境变量维护。

必需环境变量（2026-08 起无默认值，缺失启动失败属预期）：

| 变量 | 作用范围 | 说明 |
|------|----------|------|
| `CODEWISE_INTERNAL_TOKEN` | 全部 8 个服务 | 网关/下游拦截器/Feign 三处同值的内部通信 Token（各服务 yaml `codewise.internal-token: ${CODEWISE_INTERNAL_TOKEN}`，无默认值） |
| `JWT_SECRET` | service-gateway、service-message、service-review、service-user | JWT 签名密钥（user 登录时签发 token），与 Python Agent `.env` 的 `JWT_SECRET` 同值（签名算法由密钥长度自动选择） |
| `API_KEY_MASTER_KEY` | service-ai | 自定义模型 API Key 的 AES-GCM 主密钥；经属性 `security.api-key-master-key` 读取，仓库 yaml 不落盘，需通过环境变量或 Nacos 注入 |

网关 IP 信任策略：默认 `codewise.gateway.trust-forwarded-for=false` 只信任 TCP remoteAddress 并剥离客户端 X-Forwarded-For；部署在可信 LB 之后才置 true。数据库增量：`consumed_event` 幂等表共有三份同构 DDL，分别位于 `service-message/src/main/resources/sql.sql`、`service-ai/src/main/resources/sql/consumed_event.sql`（多一列 `result_ref`）、`service-review/src/main/resources/sql/sql.sql`；Outbox 表（`outboxpro_*`）由生产者服务启动时自动创建（`outboxpro.schema-initialize` 默认开，需业务账号有 CREATE 权限，部署初始化脚本已授权）

## 4. 数据库约定

业务库按服务拆分，名称采用 `codewise_<模块名>`，例如：

```text
codewise_user
codewise_question
codewise_review
codewise_community
codewise_message
```

完整建表脚本和增量迁移脚本位于各模块的 `src/main/resources`。修改表结构时必须同时维护：

1. 新环境使用的完整建表脚本。
2. 已有环境使用的增量迁移脚本。
3. 对应实体、Mapper 和查询逻辑。
4. 回滚或数据清理方案。

函数测试用例使用 `(question_id, case_hash)` 唯一键去重。现有数据库升级时执行：

```text
service-question/src/main/resources/sql/function_test_case_unique_hash.sql
```

如果已有重复用例，唯一键创建会失败，应先查询并人工确认重复数据，禁止无确认直接删除。

## 5. 构建方式

根 `pom.xml` 当前主要承担父依赖管理，没有声明 Maven `modules`。维护时按模块构建，不要使用 `-pl service-question` 从根工程选择模块。

先安装公共依赖（`service-common` 依赖 `service-api`，必须先装 api；冷环境还需先装根父 POM，各模块 pom 均声明它为 parent）：

```powershell
.\mvnw.cmd -N install
.\mvnw.cmd -f service-api\pom.xml -DskipTests install
.\mvnw.cmd -f service-common\pom.xml -DskipTests install
```

构建业务模块：

```powershell
.\mvnw.cmd -f service-question\pom.xml -DskipTests compile
.\mvnw.cmd -f service-judge\pom.xml -DskipTests compile
```

运行指定测试：

```powershell
.\mvnw.cmd -f service-question\pom.xml -Dtest=FunctionQuestionParseServiceTest test
.\mvnw.cmd -f service-judge\pom.xml '-Dtest=JudgeDebugHandlerTest' test
```

## 6. 推荐启动顺序

```text
MySQL / Redis / RabbitMQ / Nacos / Docker
  -> service-user
  -> service-question
  -> service-judge
  -> service-review
  -> service-community
  -> service-message
  -> service-ai
  -> service-gateway
```

启动后至少检查：Nacos 实例状态、数据库连接、RabbitMQ 队列绑定、Redis 连通性和 Docker 容器创建日志。

## 7. 核心判题链路

```text
客户端提交代码
  -> service-question 创建提交记录
  -> RabbitMQ 发布判题消息
  -> service-judge 查询题目和测试用例
  -> Docker 中编译一次并执行测试用例
  -> 生成 AC / WA / CE / RE / TLE 结果
  -> RabbitMQ 回传结果
  -> service-question 更新记录
  -> WebSocket 通知客户端
```

### ACM 模式

用户提交完整程序，判题器将输入写入 `input.txt`，收集 `stdout.txt`、`stderr.txt` 和进程退出码。

### 函数模式

- 从 `function_config` 读取类名、方法名、参数和返回类型。
- 生成 `Main.java`，与用户提交的 `Solution.java` 一起编译。
- 同一批测试只编译一次，再逐个替换输入运行。
- 调试接口仍使用 `/api/question/debug`，判题消费者根据题型自动分流。
- 当前函数模式只支持 Java。
- LeetCode 解析通过 GraphQL 获取题面、Java 模板和样例；输出同时兼容 `.example-block` 和旧版 `<pre>` 格式。

## 8. 判题退出码与文件

| 内容 | 含义 |
| --- | --- |
| `0` | 正常结束 |
| `2` | 平台约定的编译失败 |
| `124` | `timeout` 终止，判为 TLE |
| 其他非零值 | 通常判为 RE |
| `stdout.txt` | 用户标准输出 |
| `stderr.txt` | 编译错误或异常堆栈 |
| `exitcode.txt` | 首行编译/运行退出码，次行本次运行峰值内存（KB，需沙箱镜像内置 GNU time） |

错误摘要会做格式化，完整 JVM 异常仍保存在日志字段。修改错误展示时同时检查 `JudgeService` 和 `BuildResult`，避免两层重复处理。

## 9. 判题安全边界

已有限制（`DockerExecTemplate.createContainer` 建池时统一设置）：容器内执行、内存 256MB（含 swap 同值）、CPU 配额（period/quota 各 100ms ≈ 1 核）、PID 上限 64、禁用网络（network=none）、丢弃全部 capabilities、`no-new-privileges`、非 root 用户（uid 1000）运行、`/tmp` 落在 16m tmpfs、JVM 堆限制、运行超时与编译超时 15s（`LanguageSpec.COMPILE_TIMEOUT_MS`）。

刻意不启用只读根文件系统与 `/workspace` tmpfs：新版 Moby（29+，containerd 存储）下 `docker cp` 对只读容器直接拒绝、对 tmpfs 挂载点静默写入被遮挡路径，代码注释有完整权衡说明。

仍需评估：

- 每次提交使用独立临时容器，结束后销毁（当前以「污染即销毁重建」近似）。
- 限制输出文件与临时磁盘大小（/tmp 已限，stdout/stderr 文件尚无显式上限）。

exec 超时或异常会把容器标记为污染（tainted），归还时销毁重建、整棵进程树随之清理——超时残留后台子进程不会污染下一个用例。语言容器平时复用，`cleanContainerWorkspace()` 只清理工作目录，这正是「超时即污染重建」存在的原因。判题节点不应与数据库、消息队列等核心服务部署在同一宿主机。

### 工作区属主与内存指标

`docker cp` 由守护进程以 root 身份解包，tar 条目不带属主时落地为 `root:root`；判题用户（uid 1000）删不掉 root 属主子目录里的文件，于是 `cleanContainerWorkspace()` 的 `find -exec rm -rf` 以退出码 1 失败，函数模式（固定写 `/workspace/cases/`）**每次判题都会销毁重建容器**（实测每次多花约 2.2 秒，且容器永不复用、javac 永远冷启动）。所以 `DockerExecTemplate.copyWorkspaceToContainer()` 必须同时满足两条，缺一不可：

- 每个 tar 条目显式设置 `uid/gid = 1000:1000`（与 `JUDGE_USER` 同源）；
- 显式打包目录条目（名字以 `/` 结尾），否则子目录仍由守护进程隐式创建为 root 属主。

`memory_used` 由 run 脚本里的 GNU time（`/usr/bin/time -f %M`，单位 KB）产出，要求沙箱镜像内置 `time` 包（`deploy/judge-base/Dockerfile` 已安装）：ACM 模式写进 `exitcode.txt` 第二行，函数模式以 `__CODEWISE_MEM_KB__` 标记追加在 `batch-result.txt` 末尾，`JudgeService` 读取这两个文件时一并解析，不额外增加 Docker 调用。GNU time 缺失时判题照常，内存记 0 并打 WARN（当前 python/gcc 镜像未内置，且它们本身也还没部署）。

不要用 Docker stats 取内存：`statsCmd` 是流式异步命令、`.exec()` 立刻返回，读到的值恒为 0；cgroup v2 下 daemon 也不返回 `max_usage`，而容器 `memory.peak` 会被 javac 污染且随复用单调上升。用 `docker restart` 重置峰值同样不可行——实测单次 10.8 秒。

### 容器池运维

- Java 默认 2 个容器，Python、C、C++ 默认各 1 个。
- `idle` 表示阻塞队列中的空闲容器，`busy` 由全部容器减去空闲快照得到。
- `waiting` 仅统计没有立即取得容器、正在等待队列的请求。
- 扩容成功后容器同时进入总容器集合和空闲队列。
- 只允许删除空闲容器；忙碌容器和每种语言的最后一个容器拒绝删除。
- 容器归还时清理 `/workspace`，清理失败会删除并尝试重建该容器。
- 状态接口是当前服务实例的本地快照，多实例部署时必须同时展示实例标识，不能当作全局容器池。

管理员接口统一通过网关访问：

```text
GET    /api/judge/containers
POST   /api/judge/containers/{language}
DELETE /api/judge/containers/{language}/{containerId}
```

前端管理页建议每 2 到 3 秒轮询一次。该页面访问量低，V1 不为此维护独立 WebSocket；后续如接入 Prometheus，可直接改为指标采集。

## 10. Redis 与 RabbitMQ 维护

- 判题主链路已改为事务性 Outbox（OutboxPro，Publisher Confirm 确认投递）+ 统一消息信封：提交经 `outboxpro_outbox` 中转投递，判题队列为 `judge.submit.queue` / `judge.debug.queue` / `judge.retry.queue`（均挂 DLX），submit 消费失败经 `judge.wait.queue` TTL 延迟弹回（debug 失败写 Redis 结果不重试、retry 失败直接死信留痕），死信入 `judge.dead.queue` 并登记 `failure_submit`。架构说明、迭代手册与重放操作见 `docs/maintenance/messaging-reliability.md`。
- MQ 拓扑声明集中在 `service-common` `MqConfig`，仅当 `codewise.mq.enabled=true` 时装配：community/message/review 已在本地 yaml 显式配 true，question/judge/ai/user 依赖 Nacos 下发；缺省不声明队列，监听方会因队列不存在启动失败。
- 手动 ACK 消费者所在服务（question/judge/ai/message/review/user）本地 yaml 均显式配置 `spring.rabbitmq.listener.simple.acknowledge-mode: manual`——Spring 默认 AUTO 会与手动 ACK 叠加触发 406 双重确认、channel 崩溃循环，容器部署（Nacos 空跑）时不能依赖 Nacos 下发该项。
- 新增队列必须挂 DLX；改队列参数必须换新队列名（RabbitMQ 队列参数不可变）。
- 修改 routing key 时同时检查生产者、队列绑定和消费者。
- 消费者必须考虑重复投递，优先使用业务唯一 ID 或数据库唯一键实现幂等；消费入口统一用 `EnvelopeCodec.unwrap` 双读（信封/裸格式）。
- 调试结果应先写 Redis 并标记成功，再发送结果通知，避免客户端收到通知后查不到数据。
- Redis 双桶回写失败时不得删除旧桶；Lua 切桶和 Redisson 任务锁必须一起保留。

## 11. 常见故障排查

### 服务启动即失败

1. 缺 `CODEWISE_INTERNAL_TOKEN`：全部 8 个服务 yaml 均为 `codewise.internal-token: ${CODEWISE_INTERNAL_TOKEN}`（无默认值），缺失直接启动失败，属预期。
2. 生产者服务（question/judge/review/community）缺 `outboxpro.producer.poll-interval: 1000`：OutboxPro 默认值 `"1000ms"` 需要 Spring Framework 6.2 的 duration 解析，Boot 3.2.4 解析失败导致启动即挂；必须显式配纯毫秒。
3. 缺 `JWT_SECRET`：gateway/message/review/user 的 `jwt.secret: ${JWT_SECRET}` 无默认值。Python Agent 侧密钥不一致时，响应只有 `401`「登录状态已失效，请重新登录」（码 `4011002`），**具体原因（签名不匹配、密钥缺失、长度不足）只写服务端日志**：日志出现 `Token 校验失败: Signature verification failed` 即两端 `JWT_SECRET` 不一致；出现 `服务端 JWT 配置异常` 则是 Agent 自身密钥缺失或短于 32 字节（此时返回 `500` / 码 `5001001`）。
4. 消费者服务监听队列报「队列不存在」：`codewise.mq.enabled` 未下发（MqConfig 不装配则不声明队列，见第 10 节）。
5. service-ai 缺 `API_KEY_MASTER_KEY`：`ApiKeyCryptoService` 构造读 `security.api-key-master-key`（无默认值），缺失启动失败。
6. judge 消费后 channel 反复 406（PRECONDITION_FAILED - acknowledgement source）：`acknowledge-mode: manual` 配置丢失（见第 10 节）。

### 服务无法被调用

1. 检查 Nacos 中实例是否健康。
2. 检查 Gateway 路由和请求头透传。
3. 检查 Feign 接口路径、DTO 版本和超时。

### 提交一直 pending

1. 查 `codewise_question.outboxpro_outbox`：`status='DEAD'`（投递超限，重试台账见 `outboxpro_dead_letter` 表）或 PENDING 积压（Relay 未运行 / broker 断连，`last_error_message` 有原因）。运维端点 `/actuator/outboxpro-ops/outbox`、`/dlq` 默认不可用：需 `outboxpro.ops.enabled=true`（默认关）且把 `outboxpro-ops` 加入 actuator exposure（本地 yaml 只暴露 health,info,metrics），未开启时直接查表。
2. 检查判题消息是否进入 RabbitMQ（`judge.submit.queue` 深度）。
3. 检查 `service-judge` 消费日志（eventId / retryCount / 最终结果）与死信队列 `judge.dead.queue`。
4. 检查 Docker 容器池是否初始化成功。
5. 检查结果消息是否被题目服务消费（`question.queue`）。

### 函数题 CE 或 RE

1. 检查 `function_config.parameter_config` 是否为合法 JSON。
2. 检查生成的 `Main.java` 与方法签名是否一致。
3. 检查输入行数是否与参数数量一致。
4. 查看完整 `stderr`，不要只看格式化后的错误摘要。

### 测试用例重复

同一道题中相同输入和输出会触发 `uk_function_test_question_hash`。批量贡献接口会返回“测试用例已存在”。

## 12. 发布检查清单

- 相关模块编译和针对性测试通过；改了 `service-api`/`service-common` 先 `install` 再构建业务模块。
- SQL 完整脚本与迁移脚本同步更新。
- Nacos 配置项已在目标环境准备。
- Feign DTO、MQ 消息 DTO 保持生产者和消费者兼容；跨服务 DTO 改字段 = 生产者与全部消费者同批发布，改前 grep 全部使用点。
- 新增消费者具备幂等和失败处理；新增队列挂 DLX，改队列参数换新队列名并排空旧队列（操作手册见 `docs/maintenance/messaging-reliability.md`）。
- 日志不包含密码、Token 和用户完整代码等敏感信息。
- 判题改动验证 AC、WA、CE、RE、TLE 五类结果。
- WebSocket/SSE 改动验证断线和异常返回。
- README、接口文档和本手册同步更新。
- 容器池状态查询不会改变空闲队列，忙碌容器不能被管理接口删除。
- `data/function-artifacts`、运行时上传目录和 `hs_err_pid*.log` 未进入提交。

## 13. 当前技术债

- 根 Maven 工程尚未聚合所有模块。
- 自动化测试覆盖仍偏少，Docker 判题缺少隔离环境集成测试（本机跑全上下文测试需先起 Nacos/Redis/RabbitMQ）。
- 判题沙箱已配置 PID/CPU/内存/禁网/capabilities/非 root/编译超时；剩余：未做「每次提交一次性容器」（以复用 + 超时或异常污染销毁重建近似）、stdout/stderr 输出文件无显式磁盘上限。
- RabbitMQ 死信队列、延迟重试与失败补偿已落地（见 `docs/maintenance/messaging-reliability.md`），Outbox Relay 投递已经 Publisher Confirm 确认；剩余：非事务直发通道（邮件/WebSocket 推送、社区点赞等尽力而为通知）仍为至少一次 + 消费幂等，message 与社区部分生产端未接入 Outbox/统一信封，`message.queue` / `notification.queue` 未挂 DLX（判题、复习、申诉链路已迁移）。
- Feign 超时、熔断、降级和统一异常契约仍需收敛。
- 需要补充 traceId、结构化日志和判题资源监控。
