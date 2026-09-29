# service-gateway 网关服务

网关（端口 **8082**，Spring Cloud Gateway / WebFlux + Nacos `lb://` 负载均衡）是 CodeWise 的统一 HTTP 入口，负责路由转发、JWT 鉴权和用户身份上下文透传。前端优先通过网关访问下游服务，不应绕过网关自行拼接内部身份头。

## 路由映射（`application.yaml`，无 StripPrefix，路径原样透传）

| 路径 | 目标服务（默认端口） |
|---|---|
| `/websocket/**` | service-message（`lb:ws://`，WebSocket，8083） |
| `/api/message/**` | service-message（8083） |
| `/api/user/**`、`/uploads/**` | service-user（8081） |
| `/api/question/**` | service-question（8084） |
| `/api/judge/**` | service-judge（8086，判题主链路走 RabbitMQ，此路由仅容器管理等 HTTP 接口） |
| `/api/ai/**` | service-ai（8085） |
| `/api/review/**` | service-review（8097） |
| `/api/community/**` | service-community（8087） |

## AuthGlobalFilter（`config/AuthGlobalFilter.java`，order=-1）

先剥离、后注入：

1. **无条件剥离**客户端可伪造的 `X-User-Id`、`X-User-Name`、`X-Internal-Token`、`X-Real-IP`（`codewise.gateway.trust-forwarded-for=false` 时，默认值，连 `X-Forwarded-For` 一并剥离，只信任 TCP remoteAddress）。
2. 注入网关唯一可信值：`X-Real-IP`、`X-Internal-Token`（来自 `codewise.internal-token`）。
3. 免鉴权白名单为**精确路径**（login/register/email*/updatepassword/updatefromcode/appeal/submit 等 9 条）加 `/uploads/` 前缀，不用 contains 子串匹配。
4. 其余请求校验 `Authorization: Bearer <JWT>`（`until/JwtUntil.java`，HS 签名，subject=userId，claim `userName`），通过后注入 `X-User-Id`、`X-User-Name`。
5. WebSocket 握手（`Upgrade: websocket` 或路径含 `/websocket`）单独处理：token 先取 URL 参数 `token`（SockJS 降级），再取 Authorization 头。

下游服务由 `service-common` 的 `UserAuthInterceptor` 校验 `X-Internal-Token` 后写入 ThreadLocal `UserContext`，并在 `afterCompletion` 清理。

## 其他

- `GlobalErrorWebExceptionHandler`：WebFlux 下 `@RestControllerAdvice` 不生效，异常统一渲染为 `{code, message, data}` JSON，固定文案不外泄异常明细；401 由过滤器直接短路。
- CORS：`CORS_ORIGINS` 环境变量注入允许来源（默认本地 5173）。
- 环境变量：`JWT_SECRET`、`CODEWISE_INTERNAL_TOKEN`（均无默认值，缺失启动失败）、`NACOS_ADDR`（配置/注册中心均可选）。
- 网关无数据源且不生产事件，`outboxpro.enabled: false` 显式关闭。

## 相关文档

- [`../technical-design.md`](../technical-design.md)：网关路由、认证透传、Feign 与消息链路的整体技术设计。
- [`../maintenance-guide.md`](../maintenance-guide.md)：启动、部署和常见故障排查。
- [`../backend-controller-api.md`](../backend-controller-api.md)：接口总览与统一调用约定。
