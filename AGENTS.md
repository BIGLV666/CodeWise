# AGENTS.md

Guidance for AI coding agents working in the CodeWise monorepo.

## Big picture

CodeWise is an online-judge + review + community learning platform. It is **8 independently runnable Spring Boot apps + 2 shared Maven modules**, plus a sidecar Python FastAPI/LangGraph agent (`CodeWise-Agent/`, a sibling folder to this repo, not a submodule here).

- **Requests flow through `service-gateway` (8082)**, which validates JWT and injects identity headers, then routes to `service-user` (8081), `service-question` (8084), `service-review` (8097), `service-community` (8087), `service-message` (8083), `service-ai` (8085). `service-judge` (8086) is reached via RabbitMQ, not HTTP.
- **Each service owns exactly one database** (`codewise_user`, `codewise_question`, etc.). Never query across databases. Cross-service data goes through OpenFeign (`service-api/.../feign/`) or RabbitMQ messages.
- **Judging is async and event-driven**: `service-question` writes a submit record and publishes to RabbitMQ → `service-judge` compiles/runs in Docker → result message updates the record and notifies via `service-message`. The DB submit record is the source of truth (WebSocket push is best-effort). See `service-question/.../service/JudgeService.java` and `service-judge/.../Mq/Mq.java`.
- **Two independent AI paths**: `service-ai` consumes judge-failure events to generate suggestions + in-question SSE follow-ups; `CodeWise-Agent` is a separate LangGraph agent that calls back through the Gateway using the user's original Bearer token.

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
- MyBatis XML resources: `resources/mapper/` (review, community) vs `resources/Mapper/` (question).

## Common patterns

- **Controllers** return `Result<T>` and read identity from `UserContext`. Example: `service-user/.../controller/UserController.java`.
- **Mappers** are `@Mapper` interfaces extending MyBatis-Plus `BaseMapper<T>`; custom queries are backed by XML under the service's `resources/[Mm]apper/`.
- **Exceptions**: each service has its own `GlobalExceptionHandler` annotated `@RestControllerAdvice(basePackages = "...controller")` returning `Result.error(...)`.
- **RabbitMQ**: all queue/exchange/routing-key constants and bean definitions are centralized in `service-common` `MqContexts` + `MqConfig` (includes judge DLX/DLQ, `Jackson2JsonMessageConverter`). Reuse these constants, don't hard-code names.
- **Transactional Outbox is OutboxPro** (`io.github.biglv666:outboxpro-spring-boot-starter`, since 2026-09): publishes that must be atomic with a DB transaction call `OutboxProPublisher.publish(eventType, payload)` **inside the transaction**; routes are declared as `EventDefinition` beans in each producer service's `OutboxEventRouteConfig`. Producers: question/judge/review/community (`outboxpro.enabled: true`); other services must keep `outboxpro.enabled: false` (the lib defaults to ON when missing). `outboxpro.producer.poll-interval: 1000` (plain millis) is mandatory — the library default `"1000ms"` needs Spring Framework 6.2 and fails startup on Boot 3.2.4. Best-effort sends outside transactions (WebSocket pushes) still use `EventPublisher`/`RabbitTemplate`. Consumers parse bodies with `EnvelopeCodec.unwrap` (envelope/bare dual-read) and are wire-compatible with OutboxPro messages.
- **Lists** use ID-based cursor pagination; associated users/tags/likes use batch queries to avoid N+1.

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
- Since 2026-08 ALL services require `CODEWISE_INTERNAL_TOKEN` (no default; startup fails without it). The gateway injects it as `X-Internal-Token` after stripping client-forged `X-User-Id`/`X-User-Name`/`X-Internal-Token`/`X-Real-IP`; downstream interceptors/Feign read the same value from config. `service-gateway`, `service-message`, `service-review` also require `JWT_SECRET`. Gateway trusts client IP only from remoteAddress unless `codewise.gateway.trust-forwarded-for=true`.
- Custom model keys are encrypted with `API_KEY_MASTER_KEY` (AES-GCM); never commit real keys. Runtime function artifacts land in `data/function-artifacts/` (not versioned).
- Python agent: `JWT_SECRET` must match Java `jwt.secret` or it returns `401 Token 签名无效`; config via `CodeWise-Agent/.env`, tables from `CodeWise-Agent/sql/agent_tables.sql`.

## Where to learn more

`docs/technical-design.md`, `docs/project-structure.md`, `docs/backend-controller-api.md`, `docs/service-ai/README.md`, `docs/maintenance-guide.md`, `docs/architecture-and-highlights.md` (full architecture + business-flow diagrams + interview talking points).

