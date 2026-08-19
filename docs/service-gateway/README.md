# service-gateway 网关服务

网关是 CodeWise 的统一 HTTP 入口，负责路由转发、JWT 鉴权和用户身份上下文透传。前端优先通过网关访问下游服务，不应绕过网关自行拼接内部身份头。

## 相关文档

- [`../technical-design.md`](../technical-design.md)：网关路由、认证透传、Feign 与消息链路的整体技术设计。
- [`../maintenance-guide.md`](../maintenance-guide.md)：启动、部署和常见故障排查。
- [`../backend-controller-api.md`](../backend-controller-api.md)：接口总览与统一调用约定。
