# CodeWise 部署运维手册（Operations）

本文面向把 CodeWise 用于真实环境、需要维护能力的使用者。五分钟跑通请看 [`README.md`](README.md)（Quickstart）。

## 1. 部署拓扑与数据卷

| 组件 | 容器 | 持久化卷 | 说明 |
| --- | --- | --- | --- |
| MySQL | mysql:8.4 | `mysql-data` | 7 库初始化脚本挂载只读 |
| Redis | redis:7 | `redis-data` | AOF 开启 |
| RabbitMQ | rabbitmq:3.13 | `rabbitmq-data` | 管理台 15672 仅映射宿主回环（`127.0.0.1:15672`），不对公网暴露 |
| Nacos | nacos:2.3.2 standalone | `nacos-data` | 服务发现 + 可选配置中心，控制台仅映射宿主回环（`127.0.0.1:8848`） |
| Mailpit | axllent/mailpit | 无（内存） | 开发/演示 SMTP 捕获，Web `127.0.0.1:8025`；真实 SMTP 经 `.env` 的 `SPRING_MAIL_*` 切换 |
| Python Agent | codewise/codewise-agent | 无（会话状态在 codewise_ai 库；容器内 `data/` 的会话/token 文件不持久化，重建后自愈） | FastAPI + Node dsh 运行时，nginx `/agentapi/` 反代，JWT 与网关同 `JWT_SECRET` |
| 函数产物 | service-question | `question-artifacts` | 容器内 `/app/data/function-artifacts` |
| 用户上传 | service-user | `user-uploads` | 容器内 `/app/data/uploads`，经 Nginx `/uploads/` 反代 |
| Docker daemon | judge + question | `/var/run/docker.sock` | **DooD**：判题沙箱运行在宿主 Docker 上 |

## 2. Docker daemon 访问（DooD）的安全边界

- 挂载 `docker.sock` 等同授予容器宿主 root 等价权限。仅运行本项目自建可信镜像时风险可控；**生产建议改为独立判题节点**：judge 的 `docker.host` 指向远程 TLS endpoint（`DockerConfig` 强制 TLS 并拒绝明文 2375，需提供 `docker.tls-verify=true` + `docker.cert-path`）。
- 判题沙箱容器自身已配置 network none / 内存（256m）CPU（配额 1 核）PID（64）限制 / 非 root（uid 1000）+ capabilities 全清 + no-new-privileges + tmpfs `/tmp` / 超时销毁重建，与本地开发一致（见 `DockerExecTemplate.createContainer`；注意**未启用只读 rootfs**——新版 Moby/containerd 下 `docker cp` 会直接拒绝只读容器，判题文件经 docker cp 写入容器层）。
- judge 与 question 容器为访问 sock 显式 `user: root`，其余服务镜像均以 uid 1000 非 root 运行。

## 3. HTTPS / 域名上线

1. `.env` 的 `CORS_ORIGINS` 改为实际来源（如 `https://codewise.example.com`）；
2. Nginx（frontend 容器）前置你的 TLS 终结层，或修改 `nginx.conf` 挂证书后 `listen 443 ssl`；
3. WebSocket 走 `wss://`（Nginx 已透传 Upgrade 头）；
4. JWT 密钥保持 `.env` 的 `JWT_SECRET` 与 Python Agent `.env` 同值。

## 4. 数据库初始化与升级

初始化入口：`deploy/mysql-init/01-init-databases.sh`（仅首次建卷运行）。
脚本除建库建表外，还会对业务账号 `${MYSQL_USER}` 授权全部 `codewise_*` 库
（mysql 官方镜像默认只授予 `MYSQL_DATABASE` 一个库；OutboxPro 启动建表与日常 DML 都需要）。

| 库 | 脚本（按序） |
| --- | --- |
| codewise_user | `service-user/src/main/resources/Sql/user.sql` |
| codewise_question | `question.sql` → `function_question.sql` → `function_question_seed.sql` → judge 的 `failure_submit.sql`（死信登记表） |
| codewise_review | `sql/sql.sql` |
| codewise_community | `sql.sql` |
| codewise_message | `sql.sql`（含 notification_center、consumed_event） |
| codewise_ai | `codewise_ai.sql` → `consumed_event.sql` → `codewise-agent/sql/agent_tables.sql`（Agent 会话/消息表） |
| codewise_quiz_nest | `quiz.nest.sql`（group 与 group_member；OutboxPro 的 `outboxpro_*` 表由服务启动时自动创建） |

> 各服务的增量脚本（question 的 `function_test_case_unique_hash.sql`、review/community 的
> `migration/`、ai 的 `migration_*.sql`）**已全部合入上述基础脚本**，init 不再执行；
> 它们仅用于存量老库的手工升级。该初始化链路已用 mysql:8.4 全新卷冒烟验证
> （建库、授权、建表、业务账号可建表）。

**OutboxPro 表（outboxpro_outbox / outboxpro_inbox / outboxpro_message_log /
outboxpro_dead_letter / outboxpro_dead_letter_counter）由各生产者服务
（question/judge 共建 codewise_question 的，review 建 codewise_review 的，
community 建 codewise_community 的，quiz-nest 建 codewise_quiz_nest 的）启动时自动创建（DDL 幂等，`IF NOT EXISTS`），
前提是业务账号有对应库的 CREATE 权限（初始化脚本已授权）。**

### 存量环境升级到 OutboxPro（手工步骤）

1. **升级前排空旧 outbox**：旧 `event_outbox` 表不再被读写。确认无 PENDING
   （`SELECT COUNT(*) FROM event_outbox WHERE status='PENDING'`；有则等 Relay 投完或手工重放 DEAD）；
2. **一次性授权**：`GRANT ALL PRIVILEGES ON codewise_question.* TO '业务账号'@'%';`（其余库同理，或按初始化脚本逐库执行）；
3. 升级镜像后，各生产者服务启动时自动创建 `outboxpro_*` 表并开始 Relay；
4. 旧 `event_outbox` 表可保留作历史档案，确认稳定后手工 `DROP`；
5. 旧队列（`judge.queue`/`ai.queue` 等）排空步骤见 `docs/maintenance/messaging-reliability.md`。

已有历史数据升级时**不要重建卷**。

## 5. 配置注入方式与优先级

优先级：环境变量 > Nacos 配置 > 本地 yaml。compose 已注入：

- 连接类：`SPRING_DATASOURCE_*`（各库 URL）、`SPRING_DATA_REDIS_*`、`SPRING_RABBITMQ_*`、`NACOS_ADDR`；
- 密钥类：`CODEWISE_INTERNAL_TOKEN`（全服务共用同一值）、`JWT_SECRET`（gateway/user/message/review 四个 Java 服务 + Python Agent 容器，须与 `.env` 同值）、`API_KEY_MASTER_KEY`（service-ai 经 `SPRING_APPLICATION_JSON` 映射到 `security.api-key-master-key`；Python Agent 容器直接注入同名变量）；
- 网关：`CORS_ORIGINS`（逗号分隔）；上传：`FILE_UPLOAD_ROOT=/app/data/uploads`；
- 判题：`JUDGE_IMAGE`（判题基础镜像名，默认 `codewise-java-judge:17`）、`CODEWISE_JUDGE_CONSUMERCONCURRENCY`（compose 经 `JUDGE_CONCURRENCY` 注入，默认 2，必须与容器池容量同步上调，见 `JudgeSubmitConsumer`）；
- 引导：`ROOT_PASSWORD`/`ROOT_EMAIL` 等（root 管理员一次性创建，用户名取 `ROOT_USERNAME`，默认 `admin`）；
- 邮件：`SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD`（默认容器内 mailpit:1025）+ `CODEWISE_MAIL_FROM`；
- Agent：`DSH_PROVIDER`/`DSH_DEFAULT_CONTEXT_WINDOW`/`DSH_IDLE_TIMEOUT_SECONDS`（LLM 路由与上下文窗口）。

容器内存：compose 为每服务设 `mem_limit`（384m–1.5g，judge 为吞吐主力建议 ≥1.5g），服务运行镜像 ENTRYPOINT 固定 `-XX:MaxRAMPercentage=45.0 -XX:MaxMetaspaceSize=160m -Xss512k`，堆上限按容器限额 45% 计，防止 JVM 常驻内存超限额被 OOM kill。

GC 日志：同一个 ENTRYPOINT 开了 `-Xlog:gc*`，各服务写到共享卷 `service-logs` 的 `/app/logs/gc-<模块名>.log`（按构建参数 `MODULE` 命名，同卷不冲突），`filecount=5,filesize=10M` 轮转，上限约 60M/服务。读法：

```bash
docker compose -f deploy/docker-compose.yml exec service-judge ls -la /app/logs
docker compose -f deploy/docker-compose.yml exec service-judge tail -200 /app/logs/gc-service-judge.log
```

日志随卷持久化，宿主也可直接在 `/var/lib/docker/volumes/codewise_service-logs/_data/` 读到。注意统一日志的 `file=` 输出**每次 JVM 启动会重置当前文件**（同一轮内按 filecount/filesize 轮转，已滚动出的旧文件保留），所以要看上一轮容器的 GC 数据需在重启前取走。

仍需 Nacos 下发（可选，缺失即功能降级而非启动失败）：真实 SMTP 发信账号（`service-email.yaml`，容器部署直接在 `.env` 配 `SPRING_MAIL_*` 即可）、AI Provider 列表、Ollama 摘要模型地址。

> 注意：Nacos 若已下发同名配置（如数据源），其优先级高于环境变量，切换部署方式时留意清理旧配置。

### OutboxPro 相关配置（生产者服务：question/judge/review/community/quiz-nest）

- `outboxpro.enabled`：生产者服务为 `true`；其余服务必须显式 `false`（OutboxPro 默认 `matchIfMissing=true`）；
- `outboxpro.producer.poll-interval=1000`：**必须显式配置**。OutboxPro 默认值 `"1000ms"` 需要
  Spring Framework 6.2 的 duration 解析，CodeWise 的 Boot 3.2.4（Framework 6.1）不支持，会启动失败；
- 投递语义：Relay 认领后经 **Publisher Confirm** 确认才标记 SENT；失败按 1s 起指数退避重试 5 次后转 DEAD；
- 共享连接工厂被开启 Confirm 模式（CORRELATED）：对非 Outbox 的直发只是增加异步确认开销，无行为变化；
- question 与 judge 共用 codewise_question 的同一组 `outboxpro_*` 表，双实例 Relay 由
  `FOR UPDATE SKIP LOCKED` 保证并发安全；
- **Relay 的发布模板必须用「原样字节」转换器**（`CodewiseOutboxProAutoConfiguration`
  覆盖了 `MessagePublisher`，见 `OutboxProRelaySupport`）：框架把**已序列化的信封字符串**
  交给 `convertAndSend`，若让 Relay 复用应用侧那台带 `Jackson2JsonMessageConverter` 的模板，
  信封会被再包一层 JSON 引号（JSON 引号套 JSON），消费端 `EnvelopeCodec.unwrap` 只解一层会
  解析失败、消息全被判毒消息丢弃（表现为提交长期 PENDING、死信队列被静默放行）。
  应用侧模板带 Jackson 是必需的（直发 `EventEnvelope`/`WebsocketSendDto` 等对象），
  两者编码要求相反，故分开。回归护栏见 `OutboxProRelaySupportTest` 与
  `OutboxProIntegrationTest`（后者复刻了带 Jackson 模板的生产者配置）。

## 6. 备份与恢复

```bash
# MySQL 逻辑备份
docker compose -f deploy/docker-compose.yml exec mysql \
  sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --databases codewise_user codewise_question codewise_review codewise_community codewise_message codewise_ai codewise_quiz_nest' \
  > codewise-backup-$(date +%F).sql
# 恢复：docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < backup.sql
```

命名卷位置可用 `docker volume inspect codewise_mysql-data` 查看；建议对卷所在目录做快照级备份（RabbitMQ/Nacos 同理）。

## 7. 排障清单

| 现象 | 排查 |
| --- | --- |
| 服务间 Feign 503/超时 | Nacos 控制台（`127.0.0.1:8848/nacos`，仅宿主回环可达）确认各服务注册的是容器网段 IP 而非 127.0.0.1 |
| 判题一直 PENDING | judge 是否 healthy；宿主是否有判题镜像（`docker images \| grep codewise`）；`logs -f service-judge` |
| **前端全站 502 / 页面提示「服务正在升级或维护」** | 先分辨：宿主机 `curl 127.0.0.1:8082/actuator/health` 正常就**不是后端**，而是前端 nginx 的上游地址过期。`docker logs codewise-frontend` 若满屏 `connect() failed (111: Connection refused) ... upstream: "http://<IP>:8082"`，把那个 IP 与 `docker inspect codewise-service-gateway-1` 的当前 IP 比一下即可确认。nginx 已改为 `resolver 127.0.0.11 valid=10s` + 变量 `proxy_pass`（请求时解析，容器换 IP 最多 10s 自愈），若仍发生先 `docker exec codewise-frontend-1 nginx -s reload` |
| **改了 `deploy/nginx.conf` 但没生效** | 该文件是 bind mount 单文件，而 `git pull` 用「新文件 + rename」替换：容器里的挂载仍指向**旧 inode**，`nginx -s reload` 重载的还是旧内容，`docker compose up -d` 也不会因挂载文件内容变化而重建容器。改完必须显式重建前端：`docker compose -f deploy/docker-compose.yml up -d --force-recreate frontend`（或 `docker restart codewise-frontend-1` 重新绑定挂载）。校验是否真的生效：`docker exec codewise-frontend-1 grep -n resolver /etc/nginx/conf.d/default.conf` |
| AI 产物验收失败 | question 容器内 `docker ps` 是否可用（sock 挂载 + docker CLI） |
| 登录跨域报错 | `CORS_ORIGINS` 未包含实际来源 |
| 注册收不到邮件 | 看 Mailpit Web（127.0.0.1:8025）是否有信；真实 SMTP 检查 `.env` 的 `SPRING_MAIL_*` |
| 上传头像/配图返回「文件保存失败」(5000102) | 挂载点属主问题：命名卷挂载点若在镜像里不存在，Docker 会以 `root:root` 新建，而服务以 uid 1000 运行 → 写不进去。`docker exec -u root <c> chown -R codewise:codewise /app/data/uploads` 一次性修正；镜像已把该目录建好，新建卷不会再复现 |
| 上传成功但图片 404 | `service-user` 的静态资源目录必须跟随 `file.upload.root`（容器内 `/app/data/uploads`）。镜像内已如此，若有人改了上传根目录要同步 `WebConfig#addResourceHandlers` |
| 上传成功但图片 403「禁止直接访问，请通过网关访问」 | `/uploads/**` 必须**经网关**转发（网关 `AuthGlobalFilter` 已把该前缀列为公开路径、并注入下游必需的 `X-Internal-Token`）。nginx 直连 `service-user:8081` 会 403，别改回去 |
| Agent 不可用 | `docker compose logs codewise-agent`；dsh 运行时需 node>=22.19（镜像已内置）|
| 容器反复重启 | `docker compose logs <svc>`；多为密钥缺失启动失败（属预期：无 `CODEWISE_INTERNAL_TOKEN` 不允许起服务） |

健康探针：每个服务 `GET /actuator/health`（内网直连端口，`UserAuthInterceptor` 对探针路径免 Token）。
其余 `/actuator/**`（含 outboxpro 运维端点）需 `X-Internal-Token`。

### Outbox 死信监控闭环

| 信号 | 来源 | 处置 |
| --- | --- | --- |
| 单条死信落 DLQ | ERROR/WARN 日志（logger `OUTBOXPRO_ALERT`） | 查日志定位原因 |
| 死信积压高水位 | `onHighWatermark` ERROR 日志 + 指标 `codewise.outboxpro.deadletter.backlog` | 立即人工介入 |
| 死信/出箱查询 | `GET /actuator/outboxpro-ops/dlq`、`/outbox` — **默认不可用**：需 `outboxpro.ops.enabled=true`（默认 false）且把 `outboxpro-ops` 加入 actuator exposure（镜像配置只暴露 `health,info,metrics`）。未开启时直查 `outboxpro_outbox` / `outboxpro_dead_letter` 表；端点仅只读，重放被 `CodewiseDlqReadAuthorizer` 拒绝 | 排查与统计 |
| Micrometer 指标 | `GET /actuator/metrics`（计数器 `outboxpro.publish.*`/`outboxpro.consume.*`/`codewise.outboxpro.deadletter`） | 接入 Prometheus |
| 人工重放 | 未开放 HTTP；由 DBA 按 `outboxpro_dead_letter` 台账修复后重放 | — |

人工重放授权边界：`CodewiseDlqReadAuthorizer` 只放行 `outbox:list`/`dlq:list` 只读 scope，
`outbox:replay` 被拒绝（配置层 `outboxpro.dlq.replay.enabled` 默认也是关的）。

## 8. 切换到 GHCR 镜像（发布后）

发布流水线：`.github/workflows/docker-images.yml`（当前仅手动触发，push/tag 触发待项目稳定后启用）。发布后：

```bash
# .env 增加两行
CODEWISE_IMAGE_PREFIX=ghcr.io/<owner>/codewise-
JUDGE_IMAGE=ghcr.io/<owner>/codewise-java-judge:17
```

compose 的全部 `image:` 字段已预留该前缀变量，改完 `.env` 后 `docker compose -f deploy/docker-compose.yml pull`（或直接 `up -d --pull=always`）即可拉取镜像、无需本地构建。注意：流水线当前发布 9 个服务镜像 + `codewise-java-judge` + `codewise-frontend`，**`codewise-agent` 镜像不在发布范围内**（compose 中保留了 build 上下文，该服务仍走本地构建）。

## 9. 与本地开发的差异

- 本地：`NACOS_ADDR` 默认 `localhost:8848`，数据源等由本地 Nacos 配置中心下发；
- 容器：全部经环境变量注入，Nacos 空跑即可（`optional:` 导入缺失不阻塞启动）；
- 端口映射与本地默认一致（8081-8088、8097），前端固定 80；
- `service-judge-go` 为实验性骨架（执行器桩化返回 SYSTEM_ERROR），**不参与部署**。
