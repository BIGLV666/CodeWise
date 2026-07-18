# CodeWise 项目目录说明

本文档展示仓库的主要目录结构。为便于阅读，省略了 `target`、IDE 配置、运行时上传文件和普通样板文件。

## 目录树

```text
CodeWise/
|-- docs/
|   |-- backend-controller-api.md
|   |-- codewise-flow-and-features.md
|   |-- project-structure.md
|   |-- service-community-api.md
|   |-- service-message.md
|   |-- service-review-api.md
|   |-- AI_MODULE_GUIDE.md
|   |-- project-metrics.md
|   `-- technical-design.md
|
|-- service-api/
|   `-- src/main/java/org/example/serviceapi/
|       |-- dto/                       # 跨服务传输对象
|       `-- feign/                     # OpenFeign 服务契约
|
|-- service-common/
|   `-- src/main/java/org.example.servicecommon/
|       |-- config/                    # MQ、JWT、拦截器等公共配置
|       |-- dto/                       # 公共消息对象
|       |-- RedisDto/                  # Redis Key 与缓存对象
|       |-- service/                   # 公共邮件等能力
|       `-- until/                     # 用户上下文等工具
|
|-- service-gateway/
|   `-- src/main/java/org/example/servicegateway/
|       `-- config/                    # 路由鉴权与全局过滤器
|
|-- service-user/
|   `-- src/main/java/org/example/serviceuser/
|       |-- controller/                # 登录、资料等接口
|       |-- Fegin/                     # 面向其他服务的用户查询接口
|       |-- service/                   # 用户业务逻辑
|       |-- mapper/                    # 用户数据访问
|       |-- dto/                       # 用户请求与响应模型
|       |-- mq/                        # 用户相关消息消费
|       `-- Advice/                    # 统一异常处理
|
|-- service-question/
|   `-- src/main/
|       |-- java/org/example/servicequestion/
|       |   |-- controller/            # 题目、测试点、提交接口
|       |   |-- service/               # 题库与判题入口业务
|       |   |-- handle/                # 提交、调试结果处理
|       |   |-- MQ/                    # 判题消息生产与消费
|       |   |-- Task/                  # 提交记录及 AI 状态任务
|       |   |-- mapper/                # MyBatis Mapper
|       |   `-- config/                # WebSocket 与服务配置
|       `-- resources/
|           |-- Mapper/                # MyBatis XML
|           `-- sql/                   # 题库建表脚本
|
|-- service-judge/
|   `-- src/main/java/org/example/servicejudge/
|       |-- judge/                     # 编译、执行、判定核心逻辑
|       |-- service/                   # 判题和调试处理器
|       |-- config/                    # Docker 客户端配置
|       `-- Mq/                        # 判题消息消费与结果回传
|
|-- service-review/
|   `-- src/main/
|       |-- java/org/example/servicereview/
|       |   |-- controller/            # 复习与收藏接口
|       |   |-- service/               # 复习计划业务
|       |   |-- mapper/                # 复习数据访问
|       |   |-- task/                  # 上午/晚间复习提醒定时任务
|       |   `-- vo/                    # 复习计划与记录响应模型
|       `-- resources/
|           |-- mapper/                # MyBatis XML
|           |-- migration/             # 复习表增量索引
|           `-- sql.sql                # 复习库结构
|
|-- service-community/
|   `-- src/main/
|       |-- java/org/example/servicecommunity/
|       |   |-- controller/            # 帖子、题解、评论、点赞接口
|       |   |-- service/               # 社区业务与热点排行
|       |   |-- task/                  # 点赞和浏览量批量回写
|       |   |-- mapper/                # 社区数据访问
|       |   |-- entry/                 # 数据库实体
|       |   |-- Dto/                   # 请求 DTO
|       |   |-- vo/                    # 响应 VO
|       |   |-- enums/                 # 业务来源枚举
|       |   `-- config/                # Feign、Redis 双桶等配置
|       `-- resources/
|           |-- mapper/                # MyBatis XML
|           |-- lua/                   # Redis Lua 脚本
|           |-- migration/             # 增量数据库迁移
|           `-- sql.sql                # 社区库完整结构
|
|-- service-message/
|   `-- src/main/
|       |-- java/org/example/servicemessage/
|       |   |-- config/                # 异步线程池与 Web 配置
|       |   |-- email/                 # 验证码与系统邮件
|       |   |-- mq/                    # routing key 分派与消息消费
|       |   |-- notificationcenter/    # 通知接口、持久化、消费者与 VO
|       |   `-- websocket/             # 握手鉴权、会话与实时推送
|       `-- resources/
|           `-- sql.sql                # codewise_message 表结构
|
|-- service-ai/
|   `-- src/main/
|       |-- java/org/example/serviceai/
|       |   |-- controller/            # SSE 追问和会话查询接口
|       |   |-- conversation/          # Prompt、会话、记忆和仓储
|       |   |-- entry/                 # 会话、消息和摘要实体
|       |   |-- mapper/                # MyBatis-Plus Mapper
|       |   |-- handle/                # 判题失败 AI 消费者
|       |   `-- service/               # 多模型调用与流式适配
|       `-- resources/sql/             # codewise_ai 建表与迁移脚本
|
|-- pom.xml                            # Maven 父工程与版本管理
|-- mvnw
|-- mvnw.cmd
`-- README.md
```

## 模块依赖方向

```text
业务服务 ---> service-api     跨服务 Feign 接口和 DTO
业务服务 ---> service-common  JWT、Redis、MQ、用户上下文等公共能力

service-question ---> RabbitMQ ---> service-judge
service-judge    ---> RabbitMQ ---> service-question / service-message
service-community ---> service-user / service-question
service-review    ---> service-question / service-user
service-community ---> RabbitMQ ---> service-message
service-review    ---> RabbitMQ ---> service-message
```

`service-api` 和 `service-common` 应保持低业务耦合。新增跨服务接口时，优先把契约放入 `service-api`，通用基础设施放入 `service-common`，具体业务实现仍留在所属服务。

## 数据库划分

```text
service-user      -> codewise_user
service-question  -> codewise_question
service-review    -> codewise_review
service-community -> codewise_community
service-message   -> codewise_message
service-ai        -> codewise_ai
service-judge     -> 独立判题相关存储或配置
```

每个业务服务只直接访问自己的数据库。跨领域数据通过 Feign 或消息传递获取，避免多个服务共同修改同一张业务表。
