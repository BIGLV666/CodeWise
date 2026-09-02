# agent-runtime — CodeWise Agent 的 dsh 运行时闭包

本目录是 [DeepSeek Harness (dsh)](https://github.com/deepseek-ai/deepseek-harness) 的
部署闭包：Python 门面（`../dsh_bridge`）以 JSON-RPC stdio 驱动这里的 Node 运行时，
agent 循环、工具守卫管线、JSONL 会话日志（记忆）与自动上下文压缩全部由 dsh 提供。

## 结构

```
agent-runtime/
├── package.json            # 依赖清单：@deepseek-ai/dsh-*（全系列同版本 pin）+ codewise-tools + codewise-web
├── cordis.yml              # cordis 组合：运行时启动时加载的全部插件行
└── plugins/
    ├── codewise-tools/     # CodeWise 项目工具插件（TypeScript，11 个网关工具）
    │   └── src/…           # index.ts 注册工具 / gateway.ts 内网客户端 / tools/ 分领域定义
    └── codewise-web/       # web 抓取守卫插件（SSRF host 校验 + 受控 fetch provider）
        ├── src/guard.ts    # 纯函数：URL 形态校验 + IPv4/IPv6 黑名单 + DNS 解析比对
        ├── src/provider.ts # 包一层官方 HttpFetchProvider 的守卫 provider
        ├── src/index.ts    # 插件入口：注册唯一 fetch provider 到 ctx.web
        └── test/           # node:test 单测（黑名单 / 拦截 / fail-closed）
```

## 启动链路（由 Python 门面自动完成）

1. 门面为每个会话懒启动一个运行时子进程：
   `node node_modules/@deepseek-ai/dsh-sdk-jsonrpc-demo/lib/packaged-bin.js`
2. 进程 env 注入会话专属配置（见下表），运行时按 `DSH_CORDIS_CONFIG` 装载本目录
   的 `cordis.yml`；
3. JSONL 会话日志写入 `DSH_SESSION_ROOT`，进程重启后按同 session id 恢复。

| 环境变量 | 来源 | 用途 |
|---|---|---|
| `DSH_CORDIS_CONFIG` | 门面 | cordis.yml 路径 |
| `DSH_SYSTEM_PROMPT` | 门面 | agent persona（spine 转发给 system-prompt） |
| `DSH_CONTEXT_WINDOW` | 门面 | 用户模型上下文窗口（决定 compaction 触发时机） |
| `DSH_MODEL_ID` | 门面 | 本会话使用的模型 id（pi-ai 路由的模型目录按进程声明） |
| `DSH_PROVIDER` | 门面 | 供应商路由：`codewise`（默认，llm-pi-ai 纯净 OpenAI 协议）或 `deepseek-official`（DeepSeek 官方适配器） |
| `DEEPSEEK_BASE_URL` / `DEEPSEEK_API_KEY` | 门面（解密后） | 用户自定义 OpenAI 兼容端点 |
| `CODEWISE_GATEWAY_URL` | 门面 | CodeWise 内网网关地址（工具调用目标） |
| `CODEWISE_TOKEN_FILE` | 门面（每轮刷新） | 会话私有 Bearer token 文件 |
| `EXA_API_KEY` | 门面（可选） | Exa 搜索 provider 密钥；不配置则不注册 `web_search` |
| `PERPLEXITY_API_KEY` | 门面（可选） | Perplexity 搜索 provider 密钥；两者都在时优先 Exa |
| `DSH_WEB_SEARCH_PROVIDER` | 门面（可选） | 显式路由 `exa`/`perplexity`，避免多 provider 歧义 |

## LLM 双路由（孪生适配器）

`cordis.yml` 同时挂载两个适配器，路由互不冲突，由门面初始化时的 provider 选择：

- **`codewise`（默认）**：`llm-pi-ai` 的手工声明路由，`api: openai-completions`
  纯净协议，`reasoningEfforts: false` 保证 wire 上不带任何 reasoning/thinking
  字段——对严格校验的第三方 OpenAI 兼容网关最安全。模型目录按进程注入
  （每个会话运行时只服务一个模型）。
- **`deepseek-official`（备选）**：`llm-deepseek` 直连适配器，官方 DeepSeek
  wire 格式（`thinking` 已禁用），需要 DeepSeek 缓存计费或思考模式时切换。

## 环境要求

- Node >= 22.19（Windows 只支持 node 载体；官方 exe 载体仅 linux/macOS）
- 安装依赖：`npm install`（在 agent-runtime/ 下）
- 构建插件：`npm run build:plugin`（tsc → 两个插件的 dist）
- 守卫单测：`npm run test:web-guard`（node:test）

## web 工具（web_search / web_fetch）

`cordis.yml` 挂载 dsh 官方 `web`（ctx.web 服务）+ `tool-web`（模型可见工具）+
两个搜索 provider（Exa / Perplexity），以及本地 `codewise-web` 守卫插件：

- **web_fetch**：抓取指定 HTTP(S) URL 并转成文本/降级 HTML 返回。官方
  `dsh-web-fetch-http` provider 不做内网防护，CodeWise 组合里**不挂官方
  fetch 插件**，只挂 `codewise-web` 注册的守卫版 provider：发请求前解析 host
  并拒绝 localhost/环回/私有/保留地址（IPv4/IPv6，含 DNS 重绑定前缀、IPv4-mapped、
  NAT64、6to4 内嵌等变体），解析失败即拒绝（fail-closed）。
- **web_search**：仅当 `EXA_API_KEY` / `PERPLEXITY_API_KEY` 之一存在时才注册
  （tool-web 的 `search` 配置由 env 计算）。两个 key 都在时由
  `DSH_WEB_SEARCH_PROVIDER` 显式路由（优先 Exa）。

## 代码执行（run_code / Code Mode）

`cordis.yml` 挂载 `dsh-code-runtime-worker-thread`，并把 agent-spine 的
`tools.mode` 设为 `both`：11 个网关工具照常原生直调，同时模型可用 `run_code`
写 TypeScript 程序、以 SDK 绑定调用任意已注册工具（适合多步计算/组合查询）。

> ⚠️ **信任边界**：worker 线程的隔离是「containment，不是 security boundary」，
> 信任等级等价于 bash——模型程序可以 `import` Node 内建模块（fs / child_process /
> net），因此能读宿主文件（含 `.env`、`data/tokens/*`）并发起任意外网请求。
> dsh 官方也明确此边界（"do not enable where it can reach sensitive internal
> targets"）。**对外暴露前必须在 OS 层隔离**（独立用户 / 容器 / 最小权限，
> 不含任何明文密钥与 token 文件），单次运行预算已在 cordis.yml 收紧
> （computeMs 10s / maxWallMs 60s / 输出 1 MiB / 堆 256 MB）。开发/可信环境可
> 直接使用；生产部署需自行评估。

## 如何新增工具

1. 在 `plugins/codewise-tools/src/tools/<领域>.ts` 中用 dsh 的 `defineTool` 编写定义：
   `name` / `description`（模型可见）/ `parameters`（逐属性 DSL，含 required 与描述）/
   `output`（canonical schema + render）/ `execute(args, exec)` / 可选 `timeoutMs` 与 `presentCall`；
2. 在 `src/index.ts` 的 `apply` 中 `ctx.tools.register(...)`；
3. `npm run build:plugin`。

参数 schema 校验、超时、错误归一（isError）、结果截断、模型可见工具清单的自动
组装全部由 dsh 工具管线提供，无需在门面或前端做任何改动。

约定：`codewise-tools` 的网关工具不接受模型提供的 URL/路径；目标主机只来自
`CODEWISE_GATEWAY_URL`。模型可控 URL 的抓取集中在 `web_fetch`，其 SSRF 校验
由 `codewise-web` 守卫统一实现（仅 http/https，拒绝环回/私有/保留地址），
新增「模型可控 URL」类工具时复用 `codewise-web` 的 `guard.ts`，不要另写一套。

## 安全模型

- 每会话一个运行时子进程：模型端点密钥与内网 token 不跨用户共享；
- token 文件由门面每轮对话前原子刷新，工具每次请求时重新读取，token 不入日志；
- `update_review_config` 是唯一写工具，当前默认放行 + 事件审计；接入 dsh
  审批（permission-presets / pre-execute 瀑布）时无需改动工具定义。

## 与上游版本

依赖统一 pin 在 `0.1.0-rc.8`（与本地克隆文档接近的公开版本线）。升级时保持
全部 `@deepseek-ai/dsh-*` 同版本对齐，避免 rc 版本倾斜导致的类型/协议漂移。
