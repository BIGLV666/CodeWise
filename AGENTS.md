# AGENTS.md

Guidance for AI coding agents working in the CodeWise monorepo.

## Big picture

CodeWise is an online-judge + review + community learning platform. It is **8 independently runnable Spring Boot apps + 2 shared Maven modules + 1 Python agent** (`codewise-agent/`, FastAPI + a Node dsh runtime under `codewise-agent/agent-runtime/`, tests via `pytest tests/`).

- **Requests flow through `service-gateway` (8082)**, which validates JWT and injects identity headers, then routes to `service-user` (8081), `service-question` (8084), `service-review` (8097), `service-community` (8087), `service-message` (8083), `service-ai` (8085). `service-judge` (8086) is reached via RabbitMQ, not HTTP.
- **Each service owns exactly one database** (`codewise_user`, `codewise_question`, etc.). Never query across databases. Cross-service data goes through OpenFeign (`service-api/.../feign/`) or RabbitMQ messages.
- **Judging is async and event-driven**: `service-question` writes a submit record and publishes to RabbitMQ → `service-judge` compiles/runs in Docker → result message updates the record and notifies via `service-message`. The DB submit record is the source of truth (WebSocket push is best-effort). See `service-question/.../service/JudgeService.java` and `service-judge/.../Mq/Mq.java`.
- **Two independent AI paths**: `service-ai` consumes judge-failure events to generate suggestions + in-question SSE follow-ups, reaching models through an OpenAI-compatible gateway (platform providers from Nacos + per-user custom configs); `codewise-agent` is a separate FastAPI facade driving a dsh Node tool runtime (cordis plugin tools) that calls back through the Gateway using the user's original Bearer token (self-validates JWT with the shared `JWT_SECRET`; DB tables `agent_*` in `codewise_ai`). The agent does NOT read Nacos — its model config comes only from per-user `user_ai_config` rows owned by `service-ai` — and it does not need `CODEWISE_INTERNAL_TOKEN` (the Gateway injects it). In Docker the agent is exposed via nginx `/agentapi/` only (no host port; `JWT_SECRET`/`API_KEY_MASTER_KEY`/`DATABASE_URL`/`CODEWISE_GATEWAY_URL` are required), and the frontend builds with `VITE_AGENT_BASE_URL=/agentapi`.

## Shared modules — where things belong

- `service-api`: cross-service **Feign contracts** (`feign/`) and **DTOs** (`dto/`). The HTTP wrapper `Result<T>` lives HERE (not in service-common). Use `Result.success(...)` / `Result.error(...)`; default `code=200`.
- `service-common`: infrastructure only — JWT, Redis, RabbitMQ config, `UserContext`. Keep business logic out.
- When adding a cross-service call: put the contract in `service-api`, shared infra in `service-common`, and keep the implementation in the owning service.

## Identity propagation (critical convention)

1. `service-gateway` `AuthGlobalFilter` validates the JWT and injects `X-User-Id`, `X-User-Name`, `X-Internal-Token`.
2. Downstream `UserAuthInterceptor` (in `service-common`) validates the internal token, populates `UserContext` (a ThreadLocal), and clears it in `afterCompletion`.
3. Controllers/services read the caller via `UserContext.getUserId()` — do NOT accept `userId` as a client-supplied parameter.
4. Service-to-service Feign calls re-propagate the headers via `FeignRequestInterceptor` (reads from `UserContext`).

The Python agent mirrors this: it never trusts a model-generated `userId`; it derives the user from the token `sub` and forwards the original token through the Gateway.

## Package-naming quirks (match existing casing, do not "fix")

These are intentional/legacy and inconsistent across services. Follow the casing already present in the service you are editing:

- `until` (not `util`) for `UserContext`/JWT utils in `service-common`.
- `Fegin` in `service-user` — and `Fegin.User` is actually a `@RestController`, not a Feign client. Real Feign clients are `feign/` in `service-api`.
- MQ packages: `MQ` (question, ai), `Mq` (judge), `mq` (user, message). The judge consumer class is literally named `Mq`.
- Exception advice: `Advice` (review, user, question) vs `advice` (ai).
- Entities live in `entry/` packages. DTO casing varies: `Dto`, `dto`, `RedisDto`.
- MyBatis XML resources: `resources/mapper/` (judge, review, community) vs `resources/Mapper/` (question).
- `service-user` is the only service whose config file is `application.yml` (others use `application.yaml`); its config package is `Config/` (capitalized) and `service-judge` uses `Util/` while `service-common` uses `until/`.
- `service-question` has BOTH `Feign/` and a misspelled `FeugnService/` package; `service-api` uses `mqMessages/` (camelCase directory).

## Common patterns

- **Controllers** return `Result<T>` and read identity from `UserContext`. Example: `service-user/.../controller/UserController.java`.
- **API governance** — all modules depend on `io.github.biglv666:api-governance-spring-boot-starter` (version managed in the root POM, via `service-common`), and the 6 servlet business services (user, question, review, community, message, ai) set `api.governance.enabled: true` with a Redis sliding-window rate limiter; `service-judge` carries the dependency but has no `api.governance` config block, and the gateway (WebFlux) does not use it. Management endpoints live at `/api/<service>/governance`. New controller methods get a `@RateLimit(limit = N, window = 60)` annotation (read/write split: ~5–30/min for writes, ~100–300/min for reads); `default-limit: -1` means only annotated routes are limited. The key resolver in `service-common` `RateLimiterConfig` limits by `#user:<userId>` when logged in and `#ip:<clientIp>` otherwise with a 20× enlarged quota — keep that behavior when adding limits.
- **Admin-only endpoints** are marked `@RequireAdmin` (class or method level) from `service-common` `aop/`; `AdminAuthAspect` is a governance `PreFilter` auto-registered via `META-INF/spring/...AutoConfiguration.imports`, re-queries `roleId` through `UserFeignClient`, and rejects by `FilterContext.setRejectStatus(...)` + `setRejectReason(...)` + `return false` — never throw inside the aspect (the filter chain would wrap it into a meaningless "过滤器异常"). `roleId` 2 = admin, 0 = root. `rejectStatus` defaults to **429** (it is rate-limit oriented), so a permission aspect MUST set it explicitly (401 unauthenticated / 403 not-admin) or `AbstractGlobalExceptionHandler` will report a permission denial as rate-limited code `4290001`.
- **Mappers** are `@Mapper` interfaces extending MyBatis-Plus `BaseMapper<T>`; custom queries are backed by XML under the service's `resources/[Mm]apper/`.
- **Exceptions & error codes (since 2026-09)**: throw `BizException(SomeErrorCode.CONSTANT)` — never a bare `RuntimeException`/`IllegalArgumentException` with an inline string, and never `e.getMessage()` as user-facing text. Messages live in enums implementing `service-api` `exception/ErrorCode` (`getCode()`/`getMessage()`): `CommonErrorCode` (service number 00, cross-service semantics — reuse it, don't redefine synonyms), `InfraErrorCode` (08, service-common internals), `GatewayErrorCode` (09, WebFlux side, mirrors CommonErrorCode texts), and one enum per service (`<service>/exception/XxxErrorCode.java`, service numbers 01 user / 02 question / 03 review / 04 community / 05 message / 06 ai / 07 judge / 11 quiz-nest). Code formula: `category(3 digits)*10000 + serviceNumber*100 + seq` (categories 400/401/403/404/409/429/500), so HTTP stays 200 and clients branch on `Result.code !== 200`. Enum messages are user-facing Chinese only — no `null`, SQL, class names, HTTP numbers or downstream error text; use `%s` placeholders plus `new BizException(code, args...)` for anything dynamic, and pass the original throwable as `cause` so it reaches logs only. Each service's `GlobalExceptionHandler` extends `service-common` `advice/AbstractGlobalExceptionHandler` (which maps BizException→its own message and everything else→generic Chinese text; `GovernanceException`→status-derived code) and only declares its `basePackages`. Python agent mirrors this in `codewise-agent/util/error_codes.py` (`AgentErrorCode`, service number 10).
- **RabbitMQ**: all queue/exchange/routing-key constants and bean definitions are centralized in `service-common` `MqContexts` + `MqConfig` (includes judge DLX/DLQ, `Jackson2JsonMessageConverter`). Reuse these constants, don't hard-code names.
- **Transactional Outbox is OutboxPro** (`io.github.biglv666:outboxpro-spring-boot-starter`, since 2026-09): publishes that must be atomic with a DB transaction call `OutboxProPublisher.publish(eventType, payload)` **inside the transaction**; routes are declared as `EventDefinition` beans in each producer service's `OutboxEventRouteConfig`. Producers: question/judge/review/community (`outboxpro.enabled: true`); other services must keep `outboxpro.enabled: false` (the lib defaults to ON when missing). `outboxpro.producer.poll-interval: 1000` (plain millis) is mandatory — the library default `"1000ms"` needs Spring Framework 6.2 and fails startup on Boot 3.2.4. Best-effort sends outside transactions (WebSocket pushes) still use `EventPublisher`/`RabbitTemplate`. Consumers parse bodies with `EnvelopeCodec.unwrap` (envelope/bare dual-read) and are wire-compatible with OutboxPro messages.
- **Lists** use ID-based cursor pagination; associated users/tags/likes use batch queries to avoid N+1.
- **Long ID 序列化（JS 精度）**：实体 ID 默认 `BIGINT AUTO_INCREMENT`（自增，安全 < 2^53）。只有雪花 ID（`IdType.ASSIGN_ID`，当前仅 `take_down_post.id`）会超过 JS Number 2^53，其 VO 字段必须加 `@JsonSerialize(using = ToStringSerializer.class)` 序列化为字符串（见 `TakeDownPostVo.takeDownId`）。计数类 Long（`likeCount`/`total`/`completed` 等前端要做算术的）保持数字，**不要做全局 Long→String**；未来把自增 ID 改成雪花时，逐个给对应 VO 字段加该注解。

## Build & run (Windows PowerShell, no aggregator POM)

The root `pom.xml` does not aggregate modules. Install shared modules first after changing them —
**api before common** (common depends on api), plus the root POM on a cold machine
(`-N` installs just the parent; every module's pom declares it as `<parent>`):

```powershell
.\mvnw.cmd -N install
.\mvnw.cmd -f service-api\pom.xml -DskipTests install
.\mvnw.cmd -f service-common\pom.xml -DskipTests install
```

Then build/test individual services:

```powershell
.\mvnw.cmd -f service-question\pom.xml test
.\mvnw.cmd -f service-judge\pom.xml test
```

## Environment requirements

- Stack: JDK 21, Spring Boot 3.2.4, MySQL 8, Redis, RabbitMQ, Nacos, Docker. Python 3.11+ for the agent.
- `service-judge` needs a prebuilt Docker image `codewise-java-judge:17`; it pre-warms a per-language container pool (2 Java, 1 each Python/C/C++), managed via `/api/judge/containers`.
- Function-testcase generation requires the same `CODEWISE_INTERNAL_TOKEN` in `service-ai` and `service-question`.
- Since 2026-08 ALL services require `CODEWISE_INTERNAL_TOKEN` (no default; startup fails without it). The gateway injects it as `X-Internal-Token` after stripping client-forged `X-User-Id`/`X-User-Name`/`X-Internal-Token`/`X-Real-IP`; downstream interceptors/Feign read the same value from config. `service-user` (issues the login token), `service-gateway` (validates it), `service-message`, `service-review` and the Python agent also require `JWT_SECRET`, all with the same value. Gateway trusts client IP only from remoteAddress unless `codewise.gateway.trust-forwarded-for=true`.
- Custom model keys are encrypted with `API_KEY_MASTER_KEY` (AES-GCM); never commit real keys. Runtime function artifacts land in `data/function-artifacts/` (not versioned).
- Python agent: `JWT_SECRET` must match Java `jwt.secret`, otherwise the agent returns `401` with the generic text 「登录状态已失效，请重新登录」 (code `4011002`) — the signature-mismatch detail goes to logs only (a missing/short secret is a server-side config fault and returns `500` / `5001001`); config via `codewise-agent/.env` (lowercase directory), tables from `codewise-agent/sql/agent_tables.sql` (`user_ai_config` itself is created by `service-ai/src/main/resources/sql/codewise_ai.sql`). See `docs/codewise-agent/README.md`.

## Where to learn more

`docs/technical-design.md`, `docs/project-structure.md`, `docs/backend-controller-api.md`, `docs/service-ai/README.md`, `docs/codewise-agent/README.md`, `docs/maintenance-guide.md`, `docs/architecture-and-highlights.md` (full architecture + business-flow diagrams + interview talking points), `deploy/README.md` + `deploy/OPERATIONS.md` (Docker stack).

