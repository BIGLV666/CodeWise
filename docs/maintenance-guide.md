# CodeWise 维护手册

> 基线日期：2026-07-30
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
| `CODEWISE_INTERNAL_TOKEN` | 全部 8 个服务 | 网关/下游拦截器/Feign 三处同值的内部通信 Token |
| `JWT_SECRET` | service-gateway、service-message、service-review | JWT 签名密钥，与 Python Agent `.env` 的 `JWT_SECRET` 同值（签名算法由密钥长度自动选择） |
| `API_KEY_MASTER_KEY` | service-ai | 自定义模型 API Key 的 AES-GCM 主密钥；经属性 `security.api-key-master-key` 读取，仓库 yaml 不落盘，需通过环境变量或 Nacos 注入 |

网关 IP 信任策略：默认 `codewise.gateway.trust-forwarded-for=false` 只信任 TCP remoteAddress 并剥离客户端 X-Forwarded-For；部署在可信 LB 之后才置 true。数据库增量：`consumed_event` 建表见 service-review resources；Outbox 表（`outboxpro_*`）由生产者服务启动时自动创建（需业务账号有 CREATE 权限，部署初始化脚本已授权）

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

先安装公共依赖：

```powershell
.\mvnw.cmd -f service-common\pom.xml -DskipTests install
.\mvnw.cmd -f service-api\pom.xml -DskipTests install
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
| `exitcode.txt` | 编译或运行退出码 |

错误摘要会做格式化，完整 JVM 异常仍保存在日志字段。修改错误展示时同时检查 `JudgeService` 和 `BuildResult`，避免两层重复处理。

## 9. 判题安全边界

当前已有限制：容器内执行、总内存限制、JVM 堆限制、运行超时和禁用网络。

当前仍需加固：

- 设置容器 PID 数量和 CPU 配额。
- 使用非 root 用户，删除不必要 capabilities。
- 启用只读根文件系统和 `no-new-privileges`。
- 限制输出文件与临时磁盘大小。
- 编译阶段增加超时。
- 每次提交使用独立临时容器，结束后销毁。
- 超时时停止整个容器或进程组，防止后台子进程残留。

当前语言容器会复用，`cleanContainerWorkspace()` 只清理工作目录，不等于清理用户创建的后台进程。判题节点不应与数据库、消息队列等核心服务部署在同一宿主机。

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

- 判题主链路已改为事务性 Outbox（OutboxPro，Publisher Confirm 确认投递）+ 统一消息信封：提交经 `outboxpro_outbox` 中转投递，判题队列为 `judge.submit.queue` / `judge.debug.queue` / `judge.retry.queue`（均挂 DLX），延迟重试经 `judge.wait.queue` TTL 弹回，死信入 `judge.dead.queue` 并登记 `failure_submit`。架构说明、迭代手册与重放操作见 `docs/maintenance/messaging-reliability.md`。
- 新增队列必须挂 DLX；改队列参数必须换新队列名（RabbitMQ 队列参数不可变）。
- 修改 routing key 时同时检查生产者、队列绑定和消费者。
- 消费者必须考虑重复投递，优先使用业务唯一 ID 或数据库唯一键实现幂等；消费入口统一用 `EnvelopeCodec.unwrap` 双读（信封/裸格式）。
- 调试结果应先写 Redis 并标记成功，再发送结果通知，避免客户端收到通知后查不到数据。
- Redis 双桶回写失败时不得删除旧桶；Lua 切桶和 Redisson 任务锁必须一起保留。

## 11. 常见故障排查

### 服务无法被调用

1. 检查 Nacos 中实例是否健康。
2. 检查 Gateway 路由和请求头透传。
3. 检查 Feign 接口路径、DTO 版本和超时。

### 提交一直 pending

1. 查 `codewise_question.outboxpro_outbox`：`status='DEAD'`（投递超限，经 `/actuator/outboxpro-ops/outbox` 查台账）或 PENDING 积压（Relay 未运行 / broker 断连，`last_error_message` 有原因）。
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
- 判题沙箱的 PID、CPU、文件系统和进程回收限制不完整。
- RabbitMQ 死信队列、延迟重试与失败补偿已落地（见 `docs/maintenance/messaging-reliability.md`）；剩余：publisher confirm 未启用（当前至少一次 + 消费幂等）、消息（邮件/通知）与社区生产端尚未接入 Outbox/统一信封（判题与复习链路已迁移）。
- Feign 超时、熔断、降级和统一异常契约仍需收敛。
- 需要补充 traceId、结构化日志和判题资源监控。
