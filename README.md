# CodeWise

CodeWise 是面向在线编程、代码判题和错题复习场景的学习平台。主体由 8 个可独立运行的 Spring Boot 服务应用和 2 个公共 Maven 模块组成，旁路运行一个 FastAPI + LangGraph 工具 Agent。平台围绕“做题、判题、复习、交流、AI 辅助”形成完整学习闭环，并使用 Nacos、Gateway、OpenFeign、RabbitMQ、Redis 与 Docker 支撑服务协作和异步业务链路。

## 已实现能力

- 在线判题：代码提交、调试、测试点执行、结果回写与状态查询。
- Java 函数题：LeetCode 题面解析、方法签名解析、批量调试和测试生成。
- 复习系统：根据判题结果维护错题、每日复习和收藏夹。
- 学习社区：帖子、题解、评论、点赞、标签和热点排行。
- 消息通知：站内通知、邮件、RabbitMQ 消费和 WebSocket 推送。
- AI 辅助：Java AI 服务提供判题建议和题目追问；Python Agent 支持跨模块工具调用、SSE 过程事件、独立会话和长期摘要。
- 判题运维：按语言维护可复用容器池，支持空闲/占用/等待状态查询及管理员动态扩缩容。

## 系统架构

```mermaid
flowchart LR
    Client["Web / API Client"] --> Gateway["service-gateway"]
    Gateway --> User["service-user"]
    Gateway --> Question["service-question"]
    Gateway --> Review["service-review"]
    Gateway --> Community["service-community"]
    Gateway --> Message["service-message"]
    Gateway --> AI["service-ai"]
    Client --> Agent["CodeWise-Agent / FastAPI"]
    Agent -->|"Bearer Token + HTTP Tools"| Gateway
    Agent --> AgentDB["codewise_ai / Agent Tables"]
    Agent --> LLM["OpenAI-compatible Model"]

    Question -->|"判题任务"| MQ["RabbitMQ"]
    MQ --> Judge["service-judge"]
    Judge -->|"判题结果"| MQ
    MQ --> Question

    Judge --> Docker["Docker Runtime"]
    Question --> Redis["Redis"]
    Community --> Redis
    Review --> Redis
```

- Gateway 校验 JWT，并通过请求头向下游传递用户身份。
- 下游拦截器解析身份并写入 `UserContext`，业务服务继续判断题目作者、记录所有者等资源权限。
- `service-api` 维护 Feign 接口和跨服务 DTO，`service-common` 提供用户上下文、Redis、RabbitMQ 等公共配置。
- 每个业务服务维护自己的数据访问边界，跨服务数据通过 Feign 或消息传递，不使用跨库 SQL 作为常规调用方式。
- Python Agent 由前端携带 CodeWise Bearer Token 直接访问。Agent 使用与 Java 端一致的 JWT 密钥解析用户身份，调用工具时继续携带原 Token，经 Gateway 访问用户、题目、提交和复习接口。
- `service-ai` 与 Python Agent 职责不同：前者处理判题事件驱动建议和题目内追问，后者处理独立 Agent 页面中的跨模块自然语言操作。

## 服务模块

| 模块 | 默认端口 | 职责 |
| --- | ---: | --- |
| `service-gateway` | 8082 | 路由、JWT 校验、身份信息传递 |
| `service-user` | 8081 | 注册登录、用户资料和用户统计 |
| `service-question` | 8084 | 题目、测试点、提交记录和判题入口 |
| `service-judge` | 8086 | Docker 执行、编译运行和结果判定 |
| `service-review` | 8097 | 复习计划、每日复习和收藏夹 |
| `service-community` | 8087 | 帖子、题解、评论、点赞和排行 |
| `service-message` | 8083 | 邮件、站内通知、消息消费和 WebSocket |
| `service-ai` | 8085 | 判题建议、SSE 追问和会话记忆 |
| `CodeWise-Agent` | 8000 | LangGraph 推理、工具调用、Agent 会话和摘要记忆 |
| `service-api` | - | Feign 接口与跨服务 DTO |
| `service-common` | - | 用户上下文及公共 Redis、MQ 配置 |

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 基础框架 | Java 21、Spring Boot 3.2.4、Maven |
| 服务通信 | Spring Cloud Gateway、Nacos、OpenFeign |
| 数据访问 | MySQL、MyBatis-Plus、MyBatis XML |
| 缓存与并发 | Redis、Redisson、定时任务、分布式锁 |
| 异步通信 | RabbitMQ |
| 实时通信 | WebSocket、SSE |
| Python Agent | Python、FastAPI、SQLAlchemy、LangGraph、LangChain |
| 模型接入 | OpenAI-compatible API、用户自定义模型配置、AES-GCM 密钥加密 |
| 判题执行 | Docker Java API、Java 17 用户代码环境 |
| 前端 | Vue 3、TypeScript、Pinia、Element Plus、Monaco Editor |

## 核心实现

### 异步判题

`service-question` 创建提交记录并发送 RabbitMQ 任务，`service-judge` 在 Docker 中编译和运行用户代码，再通过结果消息更新提交记录。WebSocket 用于实时通知，数据库提交记录是最终可查询结果，因此用户离线或推送失败时仍可主动查询。

### Java 函数题

函数模式读取方法名和参数配置，生成 `Main.java` 调用用户的 `Solution`。同一次提交只编译一次，再逐个运行测试输入。函数返回值写入 `result.txt`，用户的 `System.out` 保留为调试日志，避免调试输出污染答案比较。

支持批量补充测试、输入输出哈希去重、标准答案异步生成测试任务。随机测试以通用类型生成器为基础，并为跨参数约束预留题目级 Generator 与 Validator 扩展点。

### AI 辅助与 Python Agent

AI 能力分为两条相互独立的链路：

- `service-ai` 消费判题失败事件，生成并持久化题目建议，再通过消息服务推送；题目内追问使用 POST SSE 返回增量文本。
- `CodeWise-Agent` 提供独立会话页面的通用助手，模型可以根据用户问题选择工具，查询真实的 CodeWise 数据后再组织回答。

Python Agent 使用 LangGraph 构建 `agent -> tools -> agent` 条件图。模型返回 `tool_calls` 时进入 `ToolNode`，工具结果以 `ToolMessage` 追加到状态；没有工具调用时结束。单次执行最多进行 10 轮模型调用，避免模型与工具之间无限循环。

当前工具覆盖：

- 查询当前用户信息、最近提交、单条或批量提交详情。
- 搜索题目和查询题目详情。
- 查询复习记录、复习计划和复习配置。
- 在用户明确要求时更新复习配置。

工具不接受模型生成的 `userId`。FastAPI 从 Bearer Token 的 `sub` 解析当前用户，`JavaClient` 将原 Token 继续传给 Gateway，由 Java 服务执行资源权限校验。

Agent 的会话、消息和摘要分别写入 `agent_conversation`、`agent_message` 和 `agent_memory`。每次调用从数据库读取摘要与未压缩消息，不使用进程内 `MemorySaver`，避免数据库历史和内存历史重复追加。当 Prompt 超过 24000 Token 时，保留最近 8 条消息并压缩更早历史，摘要通过 `summarized_message_id` 标记覆盖边界。

流式接口 `/api/agent/stream` 返回以下 SSE 事件：

| 事件 | 用途 |
| --- | --- |
| `status` | 当前处于分析阶段 |
| `tool_start` | 即将调用的工具及显示名称 |
| `tool_end` | 工具执行成功或失败 |
| `chunk` | 模型增量回答 |
| `done` | 本次会话处理完成 |
| `error` | 流开始后的异常信息 |

用户问题和完整助手回答在流正常结束后统一提交事务；生成中途失败时回滚本次消息写入。

Agent HTTP 接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/agent/agent_conversation` | 查询当前用户的会话列表 |
| `POST` | `/api/agent/conversation` | 创建新会话 |
| `PUT` | `/api/agent/conversation/{conversationId}/name` | 修改当前用户会话名称 |
| `DELETE` | `/api/agent/conversation/{conversationId}` | 删除会话及关联消息 |
| `GET` | `/api/agent/messages/{conversationId}` | 查询会话消息 |
| `POST` | `/api/agent/call` | 非流式 Agent 调用 |
| `POST` | `/api/agent/stream` | SSE 流式 Agent 调用及工具过程事件 |

### Docker 判题与容器池

判题服务通过 Docker 隔离用户代码，设置运行超时、内存限制和 `network=none`，并统一识别 AC、WA、CE、RE、TLE。

判题服务为 Java 预创建 2 个容器，为 Python、C 和 C++ 各预创建 1 个容器。请求通过按语言隔离的阻塞队列租借空闲容器，归还前清理 `/workspace`；清理失败时删除并重建容器。管理员可通过 `/api/judge/containers` 查询池状态、扩容或删除空闲容器，忙碌容器和每种语言的最后一个容器不可删除。

### 缓存、排行和分页

- Redis 保存部分高频计数和缓存数据，定时任务批量回写 MySQL。
- Redis ZSet 保存社区热点分数，定时任务负责周期性重算。
- 列表接口使用基于 ID 的游标分页；关联用户、标签和点赞状态使用批量查询减少 N+1。
- 通知和函数测试用例使用业务唯一键处理重复写入。

## 项目结构

```text
CodeWise/
|-- service-gateway/
|-- service-user/
|-- service-question/
|-- service-judge/
|-- service-review/
|-- service-community/
|-- service-message/
|-- service-ai/
|-- service-api/
|-- service-common/
|-- docs/
|-- pom.xml
`-- mvnw / mvnw.cmd

CodeWise-Agent/                 # 与 CodeWise 同级的 Python Agent
|-- api/                        # FastAPI 会话、普通调用和 SSE 接口
|-- service/                    # LangGraph、Prompt、调用编排和记忆压缩
|-- tools/                      # 绑定当前用户 Token 的工具集合
|-- util/                       # Java HTTP Client、JWT 与密钥解密
|-- entry/ + mapper/            # SQLAlchemy 模型和数据访问
|-- sql/agent_tables.sql
`-- main.py
```

## 本地运行

环境依赖：JDK 21、Python 3.11+、MySQL 8、Redis、RabbitMQ、Nacos 和 Docker。

### 首次运行或 IDE 加载失败

如果 IDE（如 IntelliJ IDEA）在加载项目时提示依赖解析失败，或 Maven 每次启动都重新加载且出错，这是因为共享模块（`service-api`、`service-common`）还未安装到本地 Maven 仓库。

使用提供的一键安装脚本：

```powershell
.\mvnw.cmd -f service-common\pom.xml -DskipTests install
.\mvnw.cmd -f service-api\pom.xml -DskipTests install
```

然后分别编译或启动所需服务：

```powershell
.\mvnw.cmd -f service-question\pom.xml test
.\mvnw.cmd -f service-judge\pom.xml test
```

判题服务所在 Docker 主机需要提前构建 `codewise-java-judge:17` 镜像，并提供 Jackson JAR。

函数测试生成链路还要求 `service-ai` 与 `service-question` 配置相同的 `CODEWISE_INTERNAL_TOKEN`。用户自定义模型密钥由 `API_KEY_MASTER_KEY` 加密，仓库中不得保存真实密钥。运行时生成的函数产物写入 `data/function-artifacts`，不纳入版本控制。

Python Agent 使用 `CodeWise-Agent/sql/agent_tables.sql` 初始化会话表，并在 `.env` 中配置：

```text
DATABASE_URL=mysql+pymysql://<user>:<password>@127.0.0.1:3306/codewise_ai?charset=utf8mb4
API_KEY_MASTER_KEY=<与 Java AI 配置加密一致的 32 字节 Base64 密钥>
CODEWISE_GATEWAY_URL=http://127.0.0.1:8082
JWT_SECRET=<与 Java jwt.secret 一致>
```

启动 Agent：

```powershell
cd ..\CodeWise-Agent
.\.venv\Scripts\python.exe main.py
```

FastAPI 默认监听 `127.0.0.1:8000`。前端调用 `/api/agent/**` 时必须携带 CodeWise 登录 Token；`JWT_SECRET` 不一致会导致 Agent 返回 `401 Token 签名无效`。

## 文档

完整文档索引见 [文档导航](docs/README.md)。

- [技术设计与核心链路](docs/technical-design.md)
- [项目目录说明](docs/project-structure.md)
- [Controller 接口总览](docs/backend-controller-api.md)
- [维护手册](docs/maintenance-guide.md)
- [复习与收藏接口](docs/service-review-api.md)
- [社区接口](docs/service-community-api.md)
- [通知中心与消息服务](docs/service-message.md)
- [社区审核与申诉 API](docs/community/review-appeal-api.md)
- [社区审核与申诉实现总结](docs/community/review-appeal-summary.md)
- [函数测试生成接口](docs/function-testcase-generator-api.md)
- [自定义 AI 配置接口](docs/custom-ai-config-api.md)
- [Java AI 模块与会话记忆](docs/AI_MODULE_GUIDE.md)
- [V1 发布基线](docs/v1-release-notes.md)
## 项目定位

本项目用于个人学习与工程实践，重点展示异步判题、函数题适配、容器池调度、学习数据闭环和 AI 辅助等核心实现及其设计过程。
