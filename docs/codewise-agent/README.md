# codewise-agent 模块说明

`codewise-agent` 是 CodeWise 的独立 Python 智能体服务：FastAPI 门面 + 每会话一个的 Node dsh（DeepSeek Harness）工具运行时子进程。它为前端对话页提供 SSE/非流式对话能力，模型通过 JSON-RPC stdio 驱动 dsh agent 循环，dsh 内的 `codewise-tools` 插件以**用户原始 Bearer token 经 service-gateway 回调**各 Java 服务，读取题目/提交/复习/社区等真实数据并可执行写操作（发帖、收藏、笔记、学习计划等）。

与 `service-ai` 的边界（一句话）：`service-ai` 是 Java 侧的判题失败建议与题内 SSE 追问（消费 MQ 事件、平台 Nacos 供应商）；`codewise-agent` 是独立部署的通用对话 Agent（工具编排 + 会话记忆），只使用用户自定义 AI 配置（`user_ai_config` 表），两者除共用 `codewise_ai` 库外无代码依赖。

## 1. 技术栈与结构

- Python 3.11+ / FastAPI / uvicorn / SQLAlchemy 2 + PyMySQL / PyJWT / cryptography（`requirements.txt`）
- Node >= 22.19：dsh 运行时闭包，`@deepseek-ai/dsh-*` 系列 pin 在 `0.1.0-rc.8`
- 测试：pytest（`tests/`），插件守卫另有 node:test

```text
codewise-agent/
|-- main.py                  # uvicorn 入口（AGENT_HOST 默认 127.0.0.1，AGENT_PORT 默认 8000）
|-- api/                     # FastAPI 路由：AgentApi（会话 CRUD）、CallAiApi（call/stream）
|-- service/                 # ConversationService（会话与消息持久化）
|-- dsh_bridge/              # Python ↔ dsh 桥接
|   |-- service.py           #   DshAgentService：一轮对话编排 + USER/ASSISTANT 消息落库
|   |-- manager.py           #   RuntimeManager：会话运行时注册表、空闲回收、atexit 关停
|   |-- runtime.py           #   DshConversationRuntime：每会话子进程、配置指纹、事件转发
|   |-- settings.py          #   环境变量集中解析（含 system prompt persona）
|   |-- token_store.py       #   会话私有 token 文件（原子写、0o600、不落日志）
|   |-- bridge.py            #   dsh 事件 → SSE 帧（透传原生事件词汇）
|   `-- sdk/                 #   vendored dsh Python SDK（JSON-RPC stdio 客户端）
|-- agent-runtime/           # dsh Node 部署闭包（详见其 README.md）
|   |-- cordis.yml           #   插件组合：双 LLM 适配器、compaction、web、codewise-tools
|   `-- plugins/codewise-tools/  # 51 个工具；plugins/codewise-web/ SSRF 守卫 fetch
|-- dto/ entry/ mapper/ util/    # Pydantic 请求模型、SQLAlchemy 实体、Mapper、JWT/AES 工具
|-- sql/agent_tables.sql     # codewise_ai 建库建表脚本
`-- tests/                   # pytest 单测 + 真实运行时冒烟脚本
```

## 2. 启动与配置

配置全部来自环境变量（`python-dotenv` 读取本目录 `.env`；参考 `.env.example`）。容器部署：

```powershell
cd codewise-agent
python -m venv .venv; .venv\Scripts\pip install -r requirements.txt
cd agent-runtime; npm ci; npm run build:plugin; cd ..
python main.py                      # 本地默认 127.0.0.1:8000
```

| 变量 | 必需 | 说明 |
|---|---|---|
| `DATABASE_URL` | 是（导入期） | 如 `mysql+pymysql://<user>:<password>@127.0.0.1:3306/codewise_ai?charset=utf8mb4`，缺失直接 `RuntimeError` |
| `JWT_SECRET` | 是 | **必须与 Java `jwt.secret` 同值**；算法按密钥 UTF-8 字节长度自动匹配 JJWT（≥64→HS512、≥48→HS384、≥32→HS256）。密钥不一致/签名无效时接口返回 HTTP `401` + 「登录状态已失效，请重新登录」（`AgentErrorCode.TOKEN_INVALID` / `4011002`）；未配置或短于 32 字节属服务端配置故障（`get_java_jwt_algorithm` 抛 `RuntimeError`），返回 HTTP `500` + 「服务繁忙，请稍后重试」（`INTERNAL_ERROR` / `5001001`）。两种情况的原始原因都只写服务端日志，不进响应体 |
| `API_KEY_MASTER_KEY` | 是 | 32 字节 Base64 主密钥（AES-GCM），与 Java `security.api-key-master-key` 同值，用于解密 `user_ai_config.api_key` |
| `CODEWISE_GATEWAY_URL` | 是 | 工具回调的内网网关地址（如 `http://127.0.0.1:8082`）；`load_settings` 用 `os.environ[...]` 强读取 |
| `AGENT_HOST` / `AGENT_PORT` | 否 | 默认 `127.0.0.1` / `8000`；Dockerfile 内置 `AGENT_HOST=0.0.0.0` |
| `DSH_NODE_BIN` | 否 | Node 可执行文件，默认 PATH 上的 `node` |
| `DSH_PROVIDER` | 否 | `codewise`（默认，llm-pi-ai 纯净 OpenAI 协议）或 `deepseek-official`（官方适配器） |
| `DSH_RUNTIME_ENTRY` / `DSH_CORDIS_CONFIG` / `DSH_RUNTIME_CWD` | 否 | 运行时入口/组合配置/子进程 cwd，默认指向 `agent-runtime/` |
| `DSH_SESSION_ROOT_BASE` / `DSH_TOKEN_DIR` | 否 | 会话 JSONL 根目录与 token 文件目录，默认 `data/dsh-sessions`、`data/tokens` |
| `DSH_IDLE_TIMEOUT_SECONDS` | 否 | 空闲运行时回收阈值，默认 600 |
| `DSH_DEFAULT_CONTEXT_WINDOW` | 否 | 注入模型的上下文窗口（影响 compaction 触发），默认 65536 |
| `DSH_REQUEST_TIMEOUT_SECONDS` / `AGENT_MAX_TOKENS` | 否 | JSON-RPC 单请求超时（默认不限）/ 输出上限 |
| `DSH_TURN_TIMEOUT_SECONDS` | 否 | 整轮执行超时，默认 300 秒，包含等待会话 idle |
| `EXA_API_KEY` 或 `PERPLEXITY_API_KEY` | 否 | web_search 供应商 key；都不配则只有 web_fetch；都在时优先 Exa |

> 注意：`.env.example` 中的 `JWT_ALGORITHM` 未被任何代码读取（算法由密钥长度推导），属冗余配置。服务本身没有 `/health` 端点，Dockerfile/compose 健康检查用 `GET /docs`（Swagger UI）。

## 3. 鉴权与身份模型

- 所有业务端点要求 `Authorization: Bearer <JWT>`（`util/jwt.py` 的 `get_current_user_id` / `get_token_from_header`）。缺头部返回 HTTP `401` + 「请先登录后再使用对话功能」（`AgentErrorCode.MISSING_TOKEN` / `4011001`）；签名无效、密钥与 Java 侧不一致等解码失败一律归为 HTTP `401` + 「登录状态已失效，请重新登录」（`TOKEN_INVALID` / `4011002`），`JWT_SECRET` 未配置或长度不足则归为 HTTP `500` + 「服务繁忙，请稍后重试」（`INTERNAL_ERROR` / `5001001`），原始异常只进日志。
- 用户身份**只从 JWT 的 `sub` 声明推导**；请求体/查询参数不接受 `userId`，绝不信任模型生成的 userId。会话、消息、AI 配置每次操作都用 `user_id` 做归属过滤，越权或不存在的会话统一返回 HTTP `404` + 「对话不存在或已被删除」（`CONVERSATION_NOT_FOUND` / `4041001`）。
- 工具回调链路：门面每轮把当前 Bearer token 原子写入会话私有文件（`token_store.py`，POSIX 0o600）→ dsh 插件每次请求前重读（`gateway.ts`）→ 打到 `CODEWISE_GATEWAY_URL`，由网关注入 `X-User-Id`/`X-Internal-Token` 后到达 Java 服务。Agent 进程自身不配置、不携带 `CODEWISE_INTERNAL_TOKEN`。
- 前端到 Agent 不经过网关（nginx `/agentapi/` 直反代本服务），JWT 由 Agent 自校验。CORS 只放行 `http://localhost:5173`（开发用），生产同源走 `/agentapi/`。

## 4. 数据库（codewise_ai 库）

建表脚本 `sql/agent_tables.sql`（compose 中挂载到 mysql 初始化目录）：

| 表 | 状态 | 用途 |
|---|---|---|
| `agent_conversation` | 在用 | Agent 会话（标题、user_id、更新时间索引） |
| `agent_message` | 在用 | 消息持久化（role：SYSTEM/USER/ASSISTANT/TOOL；前端历史列表数据源；随会话级联删除） |
| `agent_memory` | **已弃用**（2026-09） | 旧对话摘要表；摘要已由 dsh compaction（JSONL 会话日志）接管，仅为回滚保留 |
| `user_ai_config` | 在用（只读） | 用户自定义模型配置（group_name、model_names JSON、ai_url、加密 api_key）；DDL 由 `service-ai/src/main/resources/sql/codewise_ai.sql` 拥有，Agent 侧按 `user_ai_config_id + user_id` 校验归属后解密使用 |

运行期还会在 `data/` 下落地非数据库状态：会话 JSONL 日志与 token 临时文件。JSONL 只是运行期工作日志——
**dsh 无法跨进程恢复会话**（恢复判定要求 seed 逐字节相等），故会话 id 为 `cw-<会话号>-<配置摘要>-<实例后缀>`，
且每个新实例的首轮由门面回放 MySQL 里的 `agent_message` 历史来接续上下文（历史事实来源是数据库）。

## 5. HTTP 端点（7 个，前缀 `/api/agent`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/agent/agent_conversation` | 当前用户会话列表 |
| POST | `/api/agent/conversation` | 新建会话（默认名「新对话」） |
| PUT | `/api/agent/conversation/{conversation_id}/name?new_name=` | 重命名会话 |
| DELETE | `/api/agent/conversation/{conversation_id}` | 删除会话（消息级联删除） |
| GET | `/api/agent/messages/{conversation_id}` | 会话消息列表 |
| POST | `/api/agent/call` | 非流式一轮对话，返回最终回答 |
| POST | `/api/agent/stream` | SSE 流式一轮对话 |

`call` / `stream` 请求体（`dto/CallAiRequest.py`）：`user_config_id`、`conversation_id`、`question`（1–10000 字符）、可选 `model_name`（缺省取配置中第一个模型；必须在 `model_names` 列表内）。响应为 `{code, message, data}` 手写包装（`dto/Result.py`，非 service-api 的 Java `Result<T>`）。

SSE 事件契约（`dsh_bridge/bridge.py`，透传 dsh 原生词汇）：过程帧 `session.event`（`assistant/chunk`、`tool/call`、`tool/result`、`turn/start|end`、`compaction/*`）；终止帧 `done`（含最终全文）/ `error`。`error` 帧的 `message` 是面向用户的固定文案（由 `dsh_bridge/service.py` 的 `_user_message` 翻译），不带堆栈、密钥与下游原始报错：同会话上一轮未结束 →「上一轮对话还在进行中，请稍候再发送新消息」；运行时通信故障 →「Agent 服务中断，请重试」；回答为空 →「模型未返回有效内容」；其余 →「这次回复没有成功，请稍后重试」。HTTP 响应一旦开始就无法再改状态码，生成器自身抛出的异常由 `api/CallAiApi.py` 兜底成 `error` 帧，文案取 `AgentErrorCode.AGENT_CALL_FAILED`「这次回复没有成功，请稍后重试」（`4001001`）。非流式 `/call` 用 HTTP 状态码表达同类失败：会话不存在 → `404`，调用失败 → `400`。全部错误码集中在 `util/error_codes.py`（`AgentErrorCode`，服务号 10，编码规则与 Java 侧 `CommonErrorCode` 一致）。

## 6. dsh 运行时桥接生命周期

`DshAgentService.stream_turn`：校验参数与会话归属 → 解密用户 AI 配置 → 取得会话轮次所有权 → `RuntimeManager.obtain(conversation_id, ai_config)`。

- 每会话一个 Node 子进程（懒启动、跨轮复用）；轮次所有权防止重复请求或配置切换打断正在运行的轮次。
- 后台守护线程按 `DSH_IDLE_TIMEOUT_SECONDS` 回收空闲进程，运行中的会话不参与回收；`atexit` 统一关停。
- 子进程 env 只注入会话级凭据（模型 key、token 文件路径、网关地址、DSH_MODEL_ID/CONTEXT_WINDOW/SYSTEM_PROMPT 等），模型密钥不落盘。
- 运行时失败、空回答或非 `completed` 结束原因均按 error 收尾并关闭运行时、清理 token；跨线程异常显式保留原始 traceback，客户端只接收安全文案。
- 成功完成后，USER/ASSISTANT 消息在一个数据库事务中提交，提交成功才发送 done；失败或取消轮次不新增历史消息。done 帧的网络交付不是数据库事务的一部分，客户端未收到时可重新加载历史确认结果。
- SSE 监听 HTTP 断开，前端在离开页面、切换/删除会话或清空页面时使用 AbortController 取消请求。取消只影响当前轮次，释放会话所有权并回收子进程和 token；有界队列避免无人消费时无限积压。
- `DSH_TURN_TIMEOUT_SECONDS` 默认 300 秒，覆盖等待模型、工具和会话 idle 的整轮执行，超时返回 error 并回收资源。原始 JSONL 文件保留供排障和恢复，不自动删除；取消不能回滚工具已完成的业务写入。


## 7. 工具清单 → Java 接口映射

工具目标受**静态路径白名单**约束（`plugins/codewise-tools/src/gateway.ts`）：主机/路径模型不可控，仅参数与请求体可由模型提供；白名单外 fail-closed。共 51 个注册工具，其中 50 个经网关回调（`get_current_time` 本地计算）。工具名到 Java 端点映射：

完整清单（工具名 → 网关方法+路径 → 落点 Java 接口）：

基础与题目（service-question / service-user）：

| 工具 | 方法 + 网关路径 | 落点 |
|---|---|---|
| `get_current_time` | 不经网关（本地计算） | — |
| `get_user_info` | GET `/api/user/getuserbyid` | service-user 网页端接口 |
| `search_question` | GET `/api/question/likeserach` | service-question 网页端接口 |
| `get_question_by_id` | GET `/api/question/getquestionbyid` | service-question 网页端接口 |
| `get_question_details` | POST `/api/question/agent/detail` | `AgentQuestionController`（批量详情，逐题可见性判定，单次 ≤10 题） |
| `get_recent_submissions` | GET `/api/question/getsubmitrecordsbyuserid` | service-question（列表投影不含代码与 userId） |
| `get_submission_by_id` | GET `/api/question/getsubmitrecordbyid` | service-question |
| `get_submissions_by_ids` | POST `/api/question/getsubmitrecordsbyids` | service-question |

复习与收藏夹（service-review）：

| 工具 | 方法 + 网关路径 | 落点 |
|---|---|---|
| `get_review_record` | GET `/api/review/review/reviewrecord` | 网页端接口 |
| `get_user_review_config` | GET `/api/review/review/config` | 网页端接口 |
| `get_all_review` | GET `/api/review/review/allreview` | 网页端接口 |
| `update_review_config` | PUT `/api/review/review/config` | 网页端接口（唯一写复习配置的工具） |
| `list_favorite_folders` | GET `/api/review/agent/favorites` | `AgentFavoritesController`（收藏夹瘦身 VO） |
| `get_favorite_questions` | GET `/api/review/agent/favorites/questions` | 同上 |
| `create_favorite_folder` | POST `/api/review/agent/favorites` | 同上 |
| `update_favorite_folder` | PUT `/api/review/agent/favorites` | 同上 |
| `delete_favorite_folder` | DELETE `/api/review/agent/favorites` | 同上 |
| `add_questions_to_favorite` | POST `/api/review/agent/favorites/questions` | 同上（批量增题） |
| `remove_questions_from_favorite` | DELETE `/api/review/agent/favorites/questions` | 同上 |
| `move_questions_to_favorite` | POST `/api/review/agent/favorites/move` | 同上（行锁跨夹移动） |
| `find_question_in_favorites` | GET `/api/review/agent/favorites/locate` | 同上（题目定位） |

学习计划与笔记（service-review）：

| 工具 | 方法 + 网关路径 | 落点 |
|---|---|---|
| `get_today_plan` | GET `/api/review/agent/progress/today` | `AgentProgressTrackerController` |
| `list_study_plan` | GET `/api/review/agent/progress/list` | 同上 |
| `get_plan_detail` | GET `/api/review/agent/progress/detail` | 同上 |
| `get_plan_calendar` | GET `/api/review/agent/progress/calendar` | 同上 |
| `get_weekly_report` | GET `/api/review/progress/report/week` | 网页端周报接口（前端与 agent 共用，读时聚合） |
| `create_study_plan` | POST `/api/review/agent/progress/create` | `AgentProgressTrackerController`（批量、幂等） |
| `update_study_plan` | POST `/api/review/agent/progress/update` | 同上 |
| `delete_study_plan` | POST `/api/review/agent/progress/delete` | 同上 |
| `list_note_folders` | GET `/api/review/agent/note/folders` | `AgentNoteController` |
| `create_note_folder` | POST `/api/review/agent/note/folders` | 同上 |
| `rename_note_folder` | PUT `/api/review/agent/note/folders` | 同上 |
| `delete_note_folder` | DELETE `/api/review/agent/note/folders` | 同上 |
| `list_notes` | GET `/api/review/agent/note/list` | 同上 |
| `get_note` | GET `/api/review/agent/note/detail` | 同上 |
| `create_note` | POST `/api/review/agent/note` | 同上 |
| `update_note` | PUT `/api/review/agent/note` | 同上 |
| `move_note` | POST `/api/review/agent/note/move` | 同上 |
| `delete_note` | DELETE `/api/review/agent/note` | 同上 |

社区（service-community，`AgentCommunityController`，基础路径 `/api/community/agent`）：

| 工具 | 方法 + 路径 |
|---|---|
| `list_community_posts` | GET `/posts`（latest 降序信息流） |
| `search_community_posts` | GET `/posts/search` |
| `get_community_hot_posts` | GET `/posts/hot` |
| `get_community_post` | GET `/post` |
| `create_community_post` | POST `/post` |
| `update_community_post` | PUT `/post` |
| `delete_community_post` | DELETE `/post` |
| `list_post_comments` | GET `/comments` |
| `create_community_comment` | POST `/comments` |
| `delete_community_comment` | DELETE `/comments` |
| `like_community_target` | POST `/likes`（显式终态、幂等） |
| `list_my_community_content` | GET `/mine`（「我的内容」瘦身） |

另有 dsh 侧通用工具：`web_fetch`（始终可用，经 `codewise-web` SSRF 守卫：仅 http/https、拒绝环回/私有/保留地址、fail-closed）、`web_search`（配置 Exa/Perplexity key 才注册）、`run_code`（Code Mode，worker 线程隔离，信任边界等价 bash——见 `agent-runtime/README.md` 警告，对外暴露需 OS 层隔离）。上下文压缩由 `dsh-compaction-basic` 负责（窗口 80% 触发）。

## 8. 模型接入

OpenAI 兼容协议，双适配器并存于 `cordis.yml`，由 `DSH_PROVIDER` 路由：

- **codewise（默认）**：`llm-pi-ai` 手工声明路由，纯净 `openai-completions` wire 格式（不发任何 reasoning 字段），端点即 `user_ai_config.ai_url`，密钥为解密后的 `api_key`（SDK 转注为子进程 `DEEPSEEK_BASE_URL`/`DEEPSEEK_API_KEY` env）。
- **deepseek-official**：`llm-deepseek` 官方适配器（DeepSeek 方言，思考模式已禁），需要缓存计费/思考特性时切换。

模型目录按进程注入：每个会话运行时只服务一个模型（`DSH_MODEL_ID` + `DSH_CONTEXT_WINDOW`）。平台 Nacos 供应商属 `service-ai` 路径，Agent 代码不涉及。

## 9. 部署

- 镜像：`Dockerfile` 基于 `node:22-bookworm-slim` 双运行时（apt 装 python3.11 + pip；`npm ci` 预装 agent-runtime 闭包），非 root `node` 用户运行，`EXPOSE 8000`，健康检查 `curl /docs`。构建：`docker build -t codewise/codewise-agent:latest codewise-agent/`。
- compose（`deploy/docker-compose.yml`）：服务 `codewise-agent`，不映射宿主端口；注入 `DATABASE_URL`、`JWT_SECRET`、`API_KEY_MASTER_KEY`、`CODEWISE_GATEWAY_URL=http://service-gateway:8082`、DSH 相关变量。
- nginx（`deploy/nginx.conf`）：`location /agentapi/` 剥前缀反代 `codewise-agent:8000`，禁缓冲 + `proxy_read_timeout 300s`（SSE）。前端以 `VITE_AGENT_BASE_URL=/agentapi` 构建。
- `data/`（会话 JSONL、token 文件）为运行态可丢弃数据：token 每轮刷新；JSONL 丢失**不再影响**会话记忆（记忆由门面回放 MySQL 历史接续，只损失本轮之前 dsh 未落库的过程细节）；compose 未给它挂卷，升级重建即清空。

## 10. 测试

```powershell
cd codewise-agent
.venv\Scripts\python -m pytest tests/          # 单测：API/SSE、service 编排、manager、token_store、web env
.venv\Scripts\python tests\smoke_dsh_runtime.py # 冒烟：真实 Node 运行时 + mock 模型/网关全链路
.venv\Scripts\python tests\smoke_web_tools.py   # 冒烟：web_search/web_fetch 工具链
cd agent-runtime; npm run test:web-guard         # SSRF 守卫 node:test
```

pytest 用临时 SQLite 与占位密钥（`tests/conftest.py` 在导入项目模块前设置 env），不依赖 MySQL/Java 服务/真实 Key；冒烟脚本要求本机 Node >= 22.19 且 `agent-runtime` 依赖已安装。

## 11. 安全边界与已知限制

- **凭据隔离**：每会话独立运行时子进程，模型密钥与用户 token 不跨用户共享；密钥/token 只进子进程 env 或 0o600 token 文件，不落库、不进日志；错误帧文案统一翻译，不回传堆栈与内部信息。
- **SSRF 双防线**：网关工具钉死部署配置的 origin + 静态路径白名单（fail-closed）；模型可控 URL 只出现在 `web_fetch`，由 `codewise-web` 守卫拦截环回/私有/保留地址（含 DNS 重绑定前缀、IPv4-mapped、NAT64、6to4 变体，解析失败即拒绝）。
- **写操作**：收藏/笔记/计划/社区/复习配置均为真实写接口，以用户自身 JWT 执行，权限等同网页端本人操作；当前默认放行，dsh 审批（permission-presets）可在不改工具定义的前提下接入。
- **run_code 信任边界**：worker 线程是 containment 而非 security boundary（可读 `.env`、`data/tokens/*`、发任意外网请求），对外暴露前必须 OS 层隔离；cordis.yml 已收紧单次预算（computeMs 10s / maxWallMs 60s / 输出 1 MiB / 堆 256 MB）。
- **限制**：同会话并发轮次被拒；CORS 仅开发源 `http://localhost:5173`；无 `/health`（健康检查用 `/docs`）；`agent_memory` 表已弃用；会话记忆不再依赖 `data/`（新实例首轮由门面回放 MySQL 历史，见上文），`data/` 丢失只损失 JSONL 过程细节。
