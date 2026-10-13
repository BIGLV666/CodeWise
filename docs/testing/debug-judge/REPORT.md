# 调试判题链路修复 —— 测试与压测报告

- 运行环境：本机，JDK 21 (Temurin 21.0.12.1)，Maven 3.9.16，Node v22.23.3，Docker 29.1.3，16 核 / 14 GiB
- 隔离依赖：`dbgtest-rabbit`（rabbitmq:3.13-management，宿主 127.0.0.1:5673）、`dbgtest-redis`（redis:7-alpine，宿主 127.0.0.1:6380），仅用于本次测试
- 前端：`CodeWise-frontend`，node:test 直跑真实组件 `script setup`
- 被测对象：`service-question` / `service-judge` 本次改动 + 前端判题调用链
- **未做**：生产部署、生产重启、服务器 nginx 改动、生产消息重放、git commit/push

## 1. 问题清单与覆盖映射

| # | 问题 | 触发条件 | 严重度 | 对应用例 / 验证 |
|---|---|---|---|---|
| 1 | 普通提交缺少提交类型 | 前端不传 `submitScene` | 高 | `JudgeServiceTest`（NORMAL/REVIEW/PLAN + 缺失 + 非法）；前端 5 条路由→场景用例。线上实测该请求返回 `4000207`（提交类型不能为空），与本项一致 |
| 2 | 普通结果状态读错层级 | WS 收到 `{result:{submitStatus}}` | 高 | 前端普通回调用例（外层包装/内层 result、legacy 裸结果、畸形结果） |
| 3 | `WebsocketSendDto` 无法转 JSON | 默认 `SimpleMessageConverter` 转发判题结果 | 严重 | `RabbitTemplateConfigTest`（question/judge 各 4 条）+ 集成 `RabbitTemplate` 实测 + 压测 `baseline-simple-DTO-publish`（100% 失败）→ `fixed-jackson-DTO-publish`（0% 失败） |
| 4 | 调试回调重复确认（406 unknown delivery tag） | handler 失败后外层 listener 再 NACK | 严重 | `MqTest`、`MessageAcknowledgementsTest`、`DebugHandleTest`；集成 20 项检查中 `doubleSettlements=0` |
| 5 | 回调 body 编码形态不一致 | 裸 UUID / JSON 字符串 / 事件信封 / 信封被二次 JSON 编码 | 严重 | 集成 4 种 wire 形态各 2 项检查 + `DebugHandleTest`（裸/引号/信封/二次编码信封/空白包裹） |
| 6 | 调试 HTTP/WS 双通道互相覆盖 | HTTP 返回受理信封，WS 后到 | 高 | 前端 debug 用例（受理后等待、WS 先到、业务拒绝、网络异常） |
| 7 | 调试结果丢失 | 处理中重复消息被直接丢弃 | 高 | 集成「pending duplicate 等待 ~1s 后 requeue=true」+ `DebugHandleTest` |
| 8 | 消息重复导致重复结果 | 完成后重复投递 | 中 | 集成 4 种形态的 duplicate 检查 + `DebugHandleTest` |
| 9 | 结算失败后二次结算 | ACK/NACK 抛错 | 严重 | `MessageAcknowledgementsTest`（abort 分支）+ 集成 `aborts` 计数 |
| 10 | 拓扑缺失 | Nacos 不可用 | 高 | `RabbitTemplateConfigTest`（enabled/disabled/custom Queue 三种上下文）+ 集成真实声明 16 队列/9 交换机/22 绑定 |
| 11 | 并发与吞吐 | 持续/突发提交 | 中 | 压测 sustained / spike / 模拟 HTTP + ≥30 分钟 soak |
| 12 | 同用户多标签串线 | 同用户多页面并发调试 | 中 | 协议无任务关联字段，**未消除**，为已知限制（见第 5 节） |

## 2. 单元测试结果

| 模块 | 用例 | 失败 | 错误 | 跳过 | 结论 |
|---|---:|---:|---:|---:|---|
| service-question | 168 | 0 | 0 | 2 | 通过 |
| service-judge | 49 | 0 | 0 | 1 | 通过 |
| 前端 `npm run test:judge` | 24 | 0 | 0 | 0 | 通过 |

跳过原因（均为环境变量开关，非本次回归）：

- `InternalJavaArtifactJudgeTest.shouldJudgeReadableSourceNamesThroughDockerSandbox` —— 需要 `CODEWISE_DOCKER_IT`
- `ServiceQuestionApplicationTests.contextLoads`、`ServiceJudgeApplicationTests.contextLoads` —— 需要 `CODEWISE_INFRA`

关键用例文件：`DebugHandleTest`(11)、`MessageAcknowledgementsTest`(5)、`MqTest`(3)、`RabbitTemplateConfigTest`(question 4 / judge 4)、`JudgeServiceTest`(7)、`SubmitRecordHandelTest`(9)、`TestCaseHandleTest`(5)。

## 3. 集成测试（真实类 + 隔离 RabbitMQ/Redis）

测试程序 `docs/testing/debug-judge/DebugJudgeHarness.java`，使用**真实**的 `MqConfig` 拓扑、`service-question`/`service-judge` 的 `RabbitTemplateConfig`、`DebugHandle`、`Mq` 分发器、`JudgeDebugHandler`、`JudgeDebugConsumer`；**仅**桩掉两处：判题侧题目查询（`QuestionMapper`）与代码执行（`JudgeService.executeCode`，未启动真实 Docker 判题容器）。

**20/20 通过**：

1. `MqConfig` 声明完整拓扑：queues=16, exchanges=9, bindings=22
2. question / judge 模板均使用 `Jackson2JsonMessageConverter`（各 1 项）
3. question / judge 均能把非 `Serializable` 的 `WebsocketSendDto` 发成 `application/json`（各 1 项）
4. question / judge 的 UUID 回调线上编码为带引号 JSON 字符串 `"abc-uuid"`（各 1 项）
5. `bare` / `quoted` / `envelope` / `reencoded-envelope` 四种 wire 形态 → `DebugHandle` → `message.queue`，**恰好一次 ACK**（4 项）
6. 四种形态的「已完成重复回调」只 ACK 一次且不再发第二条结果（4 项）
7. 待处理重复：等待 ~1002 ms 后 `requeue=true`，绝不 ACK 未完成工作（1 项）
8. 合法 UUID 但 Redis 无结果：恰好一次 `NACK(false)`，pending 租约被释放，channel 保持可用（1 项）
9. 畸形任务 ID：恰好一次 `NACK(false)`，不触碰 Redis（1 项）
10. 未知 routing key：恰好一次 `NACK(false)`，不崩溃（1 项）
11. 全部场景 `doubleSettlements = 0`

## 4. 压测数据

单机隔离 RabbitMQ/Redis，同一 JVM 内生产/消费，端到端 = 发布 → judge 消费 → 回调 → question 转发 → 结果计数。

| 场景 | 时长(s) | 并发 | 尝试数 | 错误数 | 错误率 | QPS | P50(ms) | P95(ms) | P99(ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline 简单转换器 发 DTO | 3.0 | 4 | 10,377 | 10,377 | **100%** | 3,458 | 0.02 | 0.05 | 0.07 |
| 修复后 Jackson 发 DTO | 5.3 | 4 | 159,056 | 0 | **0%** | 30,000 | 0.00 | 0.06 | 2.05 |
| baseline 简单转换器 回调链路 | 12.0 | 4 | 16 | 16 | **100%** | 1.33 | – | – | – |
| 修复后 回调 warmup | 5.0 | 4 | 2,460 | 0 | 0% | 492 | 6.93 | 18.57 | 27.45 |
| 修复后 回调 持续 120s | 120.0 | 8 | 323,105 | 0 | 0% | 2,693 | 2.19 | 7.30 | 11.73 |
| 修复后 回调 突发 spike | 30.0 | 32 | 93,076 | 0 | 0% | 3,099 | 8.25 | 23.39 | 33.54 |

**基线 vs 修复对比（核心结论）**：`WebsocketSendDto` 转发在默认 `SimpleMessageConverter` 下 100% 失败、JQPS≈0；换成 Jackson 后 0% 失败、DTO 直发 30,000 QPS。这正是线上「调试 WS 不返回」的消息层根因。

关于 `baseline 简单转换器 回调链路`（100% 失败，QPS 1.33）：该阶段刻意绕过 OutboxPro，用裸 `RabbitTemplate` + `SimpleMessageConverter` 直接发布裸 UUID，而 judge 消费者按 JSON 解码 `EnvelopeCodec.unwrap`，裸 UUID 不是合法 JSON → 判为毒消息丢弃。**线上该跳由 OutboxPro 信封承载，因此这不是线上真实状态**，仅作为「这一跳的编码契约必须与消费端一致」的对照，不代表线上曾经 100% 失败。修复后该跳改为 JSON 编码（带引号字符串或信封），对照阶段 0% 失败。

### 模拟 HTTP 受理 → WS 结果（45 s，16 并发）

| 指标 | 值 |
|---|---|
| HTTP 200 受理数 | 17,049 |
| 受理 QPS | 378.54 |
| 结果送达 WS 队列数 | 17,049（**100%**） |
| 仍未完成 | 0 |
| HTTP 失败 | 0 |
| 二次结算 | 0 |

**边界**：该 HTTP 端点是在 harness 内起的**模拟**受理端点（复刻生产响应形状：HTTP 200 + `Result{code,data,message}`，`data` 为任务 UUID）。**没有**真实 Spring 控制器、网关过滤器、Servlet 容器、STOMP 会话或浏览器参与。

### 稳定性 soak（1800 s，6 并发，修复后 Jackson 回调链路）

| 指标 | 值 |
|---|---:|
| 时长 | 1801.87 s |
| 并发 | 6 |
| 累计尝试 | 4,221,192 |
| 累计错误 | **0** |
| 错误率 | **0.000%** |
| 平均 QPS | 2,342.67 |
| P95（各窗口区间） | 4.03 ~ 9.45 ms |
| P99（各窗口区间） | 6.15 ~ 18.32 ms |
| 未完成积压 inFlight | 全程 0 |
| channel cache | 全程 64（无增长） |
| abort 次数 | 0 |
| 二次结算 | 0 |
| GC 后堆（各窗口） | 29.79 ~ 65.61 MiB，**无单调上升** |
| 最终 GC 后堆 | 46.9 MiB |

结论：30 分钟内错误率恒为 0，P95/P99 无漂移，无积压、无连接/channel 泄漏、无二次结算。

**内存归因（重要）**：第一轮同条件长测 GC 前堆从 55 MiB 单调升到 686 MiB。归因验证显示这是**测试程序自身**的记账——为了检测「同一 delivery tag 被结算两次」，它把每个 tag 存入集合，30 分钟内累积约 800 万条。给该集合加上界并改为每窗口采样 GC 后堆之后，堆稳定在 29.79~65.61 MiB。因此被测生产代码路径（`DebugHandle`/`Mq`/各 handler）在结算后不保留逐消息状态，未发现内存泄漏；第一轮的堆曲线不可用作生产泄漏信号。

## 5. 已知限制与未覆盖项

1. **同用户多标签串线未消除**：调试结果协议没有任务/题目关联字段，同一用户并发调试时无法把结果路由到正确的标签页。本次未伪造过滤条件。
2. **真实判题未参与**：`JudgeService.executeCode` 被桩掉，未启动 `codewise-java-judge:17` 容器，因此真实编译/执行耗时、CE/RE 真实判定不在覆盖范围内。
3. **无真实端到端浏览器验证**：未运行真实前端 + STOMP + 网关的链路测试；前端结论来自 node:test 直跑真实组件逻辑与 `npm run build` 类型检查。
4. **WebSocket 解析失败标签仍会误导**：`src/utils/WebSocket.ts` 的 `parseAndEmit` 把 `JSON.parse` 与监听器回调放在**同一个** try/catch 内，监听器抛异常会被打印成「WebSocket 消息解析失败」。前端已改为对畸形载荷提前 return 不再抛错，但该误导性日志本身未重构（全局 WS 单例改造超出本次范围）。
5. **pending 重复消息占用消费线程**：`pendingBackoffMillis` 默认 1000 ms，期间占用一个消费线程；这是为了不改动队列拓扑而给重投频率设上界，代价是线程占用。
6. **`JudgeDebugConsumer` 既有边界未改**：`service-judge` 的 `JudgeDebugConsumer` 仍是 `basicAck` 在 try 内、catch 再 `basicNack`，ACK 失败会走到 NACK。本次记录为未触发边界，未扩大生产改动。
7. **`question.queue` 无 DLX**：`NACK(requeue=false)` 是丢弃而非死信；这是既有行为，本次保留。
8. **TLS 打到明文 8086**：未做任何服务器或 nginx 改动，仅在 `deploy/README.md` 记录（8086 为明文 HTTP，HTTPS 会导致 Tomcat 解析出非法方法名）。
9. **压测环境非独占**：soak 期间宿主同时运行着用户的 Docker 开发栈（多个 `codewise-service-*` 容器反复重启，占用 200%+ CPU）。因此第 4 节的绝对 QPS / 延迟是**下界**，不宜作为干净基线；错误率、二次结算、送达率等**正确性**指标不受影响。
10. **未做生产验证**：修复未部署到线上，线上行为未复测；本次所有结论均来自本机隔离环境。
11. **单机同进程压测**：生产/消费在同一 JVM 与同一宿主，测的是消息层正确性与相对吞吐，不等价于多实例集群下的吞吐与分区行为。
12. **隔离容器为临时资源**：`dbgtest-rabbit` / `dbgtest-redis` 仅用于本次测试，测试结束即删除；未改动用户既有 compose 栈。

## 6. 复现方式

```bash
# 1) 启动隔离依赖（仅本次测试用，勿与线上/用户 compose 混用）
docker run -d --name dbgtest-rabbit -p 127.0.0.1:5673:5672 \
  -e RABBITMQ_DEFAULT_USER=debugtest -e RABBITMQ_DEFAULT_PASS=isolated-test-only \
  rabbitmq:3.13-management
docker run -d --name dbgtest-redis -p 127.0.0.1:6380:6379 redis:7-alpine

# 2) 生成模块类路径（根 POM 不聚合，需分别取）
mvn -o -f service-question/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/cp-q.txt -Dmdep.includeScope=test
mvn -o -f service-judge/pom.xml   dependency:build-classpath -Dmdep.outputFile=/tmp/cp-j.txt -Dmdep.includeScope=test

# 3) 编译四个模块 + harness 到独立目录（-proc:full 保证 Lombok 生效）
javac -nowarn -proc:full -d /tmp/harness/classes -cp "$(cat /tmp/cp-q.txt):$(cat /tmp/cp-j.txt)" \
  $(find service-api/src/main/java service-common/src/main/java \
      service-question/src/main/java service-judge/src/main/java -name '*.java')
javac -nowarn -proc:none -d /tmp/harness/classes -cp "/tmp/harness/classes:$(cat /tmp/cp-q.txt):$(cat /tmp/cp-j.txt)" \
  docs/testing/debug-judge/DebugJudgeHarness.java

# 4) 运行：<rabbitPort> <redisPort> <outDir> <soakSeconds> <mode: full|integration|soak>
java -Xmx1536m -cp "/tmp/harness/classes:$(cat /tmp/cp-q.txt):$(cat /tmp/cp-j.txt)" \
  DebugJudgeHarness 5673 6380 /tmp/harness/out 1800 full
```

