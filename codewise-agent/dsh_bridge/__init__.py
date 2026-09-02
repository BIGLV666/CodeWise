"""dsh_bridge：把 CodeWise-Agent 的 FastAPI 门面桥接到 DeepSeek Harness (dsh) 运行时。

职责边界：

- 门面保留 HTTP/SSE 契约、JWT 鉴权、会话 CRUD 与用户模型配置解密；
- dsh 运行时（Node 子进程）负责 agent 循环、工具守卫管线、JSONL 会话日志与自动压缩；
- 工具在 dsh 侧以 TypeScript 插件（agent-runtime/plugins/codewise-tools）实现，
  经内网网关（CODEWISE_GATEWAY_URL）访问 CodeWise 各服务，凭据按会话隔离。

`sdk/` 子包是上游 deepseek-harness Python SDK 的 vendor 副本（MIT），见 sdk/LICENSE。
"""
