# service-api 共享契约模块

`service-api` 存放跨服务 OpenFeign 契约和共享 DTO。它不是独立运行的 Spring Boot 服务，但文档仍按模块单独归档，便于维护服务边界。

## 维护约定

- Feign 接口放在 `feign/`。
- 跨服务 DTO 放在 `dto/`。
- HTTP 统一包装 `Result<T>` 由本模块提供。
- 具体业务实现保留在数据所属服务中。

相关整体约定见 [`../technical-design.md`](../technical-design.md) 和 [`../project-structure.md`](../project-structure.md)。
