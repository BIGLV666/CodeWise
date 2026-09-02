# CodeWise 部署运维手册（Operations）

本文面向把 CodeWise 用于真实环境、需要维护能力的使用者。五分钟跑通请看 [`README.md`](README.md)（Quickstart）。

## 1. 部署拓扑与数据卷

| 组件 | 容器 | 持久化卷 | 说明 |
| --- | --- | --- | --- |
| MySQL | mysql:8.4 | `mysql-data` | 6 库初始化脚本挂载只读 |
| Redis | redis:7 | `redis-data` | AOF 开启 |
| RabbitMQ | rabbitmq:3.13 | `rabbitmq-data` | 管理台 15672 仅映射宿主回环（`127.0.0.1:15672`），不对公网暴露 |
| Nacos | nacos:2.3.2 standalone | `nacos-data` | 服务发现 + 可选配置中心 |
| 函数产物 | service-question | `question-artifacts` | 容器内 `/app/data/function-artifacts` |
| 用户上传 | service-user | `user-uploads` | 容器内 `/app/data/uploads`，经 Nginx `/uploads/` 反代 |
| Docker daemon | judge + question | `/var/run/docker.sock` | **DooD**：判题沙箱运行在宿主 Docker 上 |

## 2. Docker daemon 访问（DooD）的安全边界

- 挂载 `docker.sock` 等同授予容器宿主 root 等价权限。仅运行本项目自建可信镜像时风险可控；**生产建议改为独立判题节点**：judge 的 `docker.host` 指向远程 TLS endpoint（`DockerConfig` 强制 TLS 并拒绝明文 2375，需提供 `docker.tls-verify=true` + `docker.cert-path`）。
- 判题沙箱容器自身已配置 network none / 内存 CPU PID 限制 / 只读根 / 非 root（uid 1000）/ 超时销毁重建，与本地开发一致（见 `docs/architecture-and-highlights.md`）。
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
| codewise_question | `question.sql` → `function_question.sql` → `function_question_seed.sql` → `function_test_case_unique_hash.sql` |
| codewise_review | `sql/sql.sql` → `migration/V2__review_schedule_index.sql` |
| codewise_community | `sql.sql` → `migration/V2__solution_and_post_type.sql` |
| codewise_message | `sql.sql`（含 notification_center、consumed_event） |
| codewise_ai | `codewise_ai.sql` → `migration_20260823_ai_message_status.sql` → `consumed_event.sql` |

**OutboxPro 表（outboxpro_outbox / outboxpro_inbox / outboxpro_message_log /
outboxpro_dead_letter / outboxpro_dead_letter_counter）由各生产者服务
（question/judge 共建 codewise_question 的，review 建 codewise_review 的，
community 建 codewise_community 的）启动时自动创建（DDL 幂等，`IF NOT EXISTS`），
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
- 密钥类：`CODEWISE_INTERNAL_TOKEN`（全服务）、`JWT_SECRET`（gateway/message/review）、`API_KEY_MASTER_KEY`（经 `SPRING_APPLICATION_JSON` 映射到 `security.api-key-master-key`）；
- 网关：`CORS_ORIGINS`（逗号分隔）；上传：`FILE_UPLOAD_ROOT=/app/data/uploads`；
- 判题：`JUDGE_IMAGE`（判题基础镜像名，默认 `codewise-java-judge:17`）；
- 引导：`ROOT_PASSWORD`/`ROOT_EMAIL` 等（root 管理员一次性创建）。

仍需 Nacos 下发（可选，缺失即功能降级而非启动失败）：SMTP 发信账号（`service-email.yaml`）、AI Provider 列表、Ollama 摘要模型地址。

> 注意：Nacos 若已下发同名配置（如数据源），其优先级高于环境变量，切换部署方式时留意清理旧配置。

### OutboxPro 相关配置（生产者服务：question/judge/review/community）

- `outboxpro.enabled`：生产者服务为 `true`；其余服务必须显式 `false`（OutboxPro 默认 `matchIfMissing=true`）；
- `outboxpro.producer.poll-interval=1000`：**必须显式配置**。OutboxPro 默认值 `"1000ms"` 需要
  Spring Framework 6.2 的 duration 解析，CodeWise 的 Boot 3.2.4（Framework 6.1）不支持，会启动失败；
- 投递语义：Relay 认领后经 **Publisher Confirm** 确认才标记 SENT；失败按 1s 起指数退避重试 5 次后转 DEAD；
- 共享连接工厂被开启 Confirm 模式（CORRELATED）：对非 Outbox 的直发只是增加异步确认开销，无行为变化；
- question 与 judge 共用 codewise_question 的同一组 `outboxpro_*` 表，双实例 Relay 由
  `FOR UPDATE SKIP LOCKED` 保证并发安全。

## 6. 备份与恢复

```bash
# MySQL 逻辑备份
docker compose -f deploy/docker-compose.yml exec mysql \
  sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --databases codewise_user codewise_question codewise_review codewise_community codewise_message codewise_ai' \
  > codewise-backup-$(date +%F).sql
# 恢复：docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < backup.sql
```

命名卷位置可用 `docker volume inspect codewise_mysql-data` 查看；建议对卷所在目录做快照级备份（RabbitMQ/Nacos 同理）。

## 7. 排障清单

| 现象 | 排查 |
| --- | --- |
| 服务间 Feign 503/超时 | Nacos 控制台（`127.0.0.1:8848/nacos`，仅宿主回环可达）确认各服务注册的是容器网段 IP 而非 127.0.0.1 |
| 判题一直 PENDING | judge 是否 healthy；宿主是否有判题镜像（`docker images \| grep codewise`）；`logs -f service-judge` |
| AI 产物验收失败 | question 容器内 `docker ps` 是否可用（sock 挂载 + docker CLI） |
| 登录跨域报错 | `CORS_ORIGINS` 未包含实际来源 |
| 注册收不到邮件 | SMTP 未配置（Quickstart 降级表） |
| 容器反复重启 | `docker compose logs <svc>`；多为密钥缺失启动失败（属预期：无 `CODEWISE_INTERNAL_TOKEN` 不允许起服务） |

健康探针：每个服务 `GET /actuator/health`（内网直连端口，`UserAuthInterceptor` 对探针路径免 Token）。
其余 `/actuator/**`（含 outboxpro 运维端点）需 `X-Internal-Token`。

### Outbox 死信监控闭环

| 信号 | 来源 | 处置 |
| --- | --- | --- |
| 单条死信落 DLQ | ERROR/WARN 日志（logger `OUTBOXPRO_ALERT`） | 查日志定位原因 |
| 死信积压高水位 | `onHighWatermark` ERROR 日志 + 指标 `codewise.outboxpro.deadletter.backlog` | 立即人工介入 |
| 死信/出箱查询 | `GET /actuator/outboxpro-ops/dlq`、`/outbox`（需内部 Token；仅只读，重放未开放） | 排查与统计 |
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

compose 的全部 `image:` 字段已预留该前缀变量，改完 `.env` 后 `docker compose up -d` 即直接拉取镜像、无需本地构建。

## 9. 与本地开发的差异

- 本地：`NACOS_ADDR` 默认 `localhost:8848`，数据源等由本地 Nacos 配置中心下发；
- 容器：全部经环境变量注入，Nacos 空跑即可（`optional:` 导入缺失不阻塞启动）；
- 端口映射与本地默认一致（8081-8087、8097），前端固定 80；
- `service-judge-go` 为实验性骨架（执行器桩化返回 SYSTEM_ERROR），**不参与部署**。
