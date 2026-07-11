# CodeWise

CodeWise 是一个面向编程学习和在线刷题的微服务项目，围绕题库、代码判题、提交记录、复习计划、消息通知和 AI 辅助能力构建。

## 核心能力

- 题库管理：题目、测试点、标签、难度、题目导入。
- 在线判题：提交代码、异步判题、判题结果回写、WebSocket 推送。
- 复习系统：基于提交结果和 SM-2 思路维护复习计划。
- 收藏夹：按用户维护题目集合。
- 社区交流：帖子、评论、回复、标签推荐和点赞。
- 用户系统：注册登录、用户资料、头像上传。
- 消息模块：统一承载邮件通知、WebSocket 推送和后续站内通知能力。
- AI 模块：用于题目解析、测试用例生成等辅助能力。

## 服务模块

| 模块 | 说明 |
| --- | --- |
| `service-gateway` | 网关、路由、鉴权、用户信息透传 |
| `service-user` | 用户注册登录、资料、头像、用户统计 |
| `service-question` | 题目、测试点、提交记录、判题入口 |
| `service-judge` | 判题执行、代码运行、判题结果生成 |
| `service-review` | 复习计划、每日复习、收藏夹 |
| `service-community` | 社区帖子、评论、回复、标签和点赞 |
| `service-message` | 邮件、WebSocket、通知能力统一模块 |
| `service-ai` | AI 相关能力 |
| `service-common` | 公共配置、MQ、Redis、上下文、公共 DTO |
| `service-api` | 服务间 Feign 接口和 DTO |

> `service-email` 已合并进 `service-message`，后续不要再单独维护邮件模块。

## 本地启动依赖

项目运行通常依赖：

- JDK 21
- Maven Wrapper
- MySQL
- Redis
- RabbitMQ
- Nacos

各服务的数据库配置主要通过 Nacos 管理，库命名约定为：

```text
codewise_<模块名去掉 service->
```

例如：

```text
service-review -> codewise_review
service-question -> codewise_question
service-user -> codewise_user
service-community -> codewise_community
```

## 常用命令

进入某个服务目录后编译：

```bash
./mvnw -DskipTests compile
```

例如编译消息模块：

```bash
cd service-message
./mvnw -DskipTests compile
```

## 文档

- [后端 Controller 接口文档](docs/backend-controller-api.md)
- [刷题复习流程与功能说明](docs/codewise-flow-and-features.md)
- [复习模块接口文档](docs/service-review-api.md)
- [社区模块接口文档](docs/service-community-api.md)
- [消息模块说明](docs/service-message.md)

## 当前开发说明

- 题目主页查询已使用游标分页。
- `submit_record` 已补充 `question_title`，用于提交历史、复习记录和通知展示。
- `service-message` 是后续通知能力的统一承载模块，邮件和 WebSocket 都应逐步收口到这里。
- `service-community` 已提供帖子、评论和点赞接口；当前需直连 `8087`，网关路由后续补充。
- `service-community` 已补充热点排行、标题模糊搜索、标签搜索、帖子修改，以及帖子和评论的异步级联删除能力。
- 社区热点榜使用 Redis ZSet，按点赞、评论和发布时间衰减计算，并每 5 分钟从数据库重建一次。
