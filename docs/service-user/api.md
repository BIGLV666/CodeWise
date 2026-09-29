# service-user 用户服务接口

本文档整理用户服务的公开 HTTP 接口。统一鉴权和响应约定见 [`../backend-controller-api.md`](../backend-controller-api.md)。

基础路径：`/api/user`（服务本体监听 8081，生产环境经网关统一访问）

鉴权约定：

- 免登录匿名接口白名单（网关与拦截器双侧放行）：`/login`、`/emaillogin`、`/emailloginforcode`、`/getemailcode`、`/emailregister`、`/register`、`/updatepasswordforemail`、`/updatefromcode`、`/appeal/submit`。
- 其余接口需登录态：网关校验 JWT 后注入 `X-User-Id` 等头，下游 `UserContext`（ThreadLocal）提供当前用户身份，接口不接受客户端自报的 `userId`。
- 管理员接口由 `@RequireAdmin`（`service-common` 的 `AdminAuthAspect`）校验：经 `UserFeignClient` 回查调用者 `roleId`，管理员（`roleId=2`）或 root（`roleId=0`）放行，否则拒绝。拒绝文案固定：未登录/回查失败为「登录状态已失效，请重新登录」（`code=4010001`），非管理员为「该功能仅限管理员使用」（`code=4030002`）。
- 所有接口均带 `@RateLimit`（Redis 滑动窗口限流），超限时 HTTP 仍为 200，返回 `code=4290001`，`message` 为「请求过于频繁，请稍后重试」。

### 错误码

业务失败的 HTTP 状态码保持 200，客户端按 `Result.code !== 200` 分支；七位错误码规则（`类别(3位)×10000 + 服务号(2位)×100 + 序号(2位)`，本服务服务号为 `01`，形如 `4010101`）与通用映射见 [`../backend-controller-api.md`](../backend-controller-api.md) 的「错误码与业务异常」一节。本服务专属语义收录在 `service-user` 的 `exception/UserErrorCode.java`（如登录失败 `4010101`、账号封禁 `4030101`）；未登录、无权限、参数、状态冲突等跨服务语义复用 `CommonErrorCode`（服务号 00）。只有 `BizException` 的文案面向用户原样返回，其余异常（含 `IllegalArgumentException`/`IllegalStateException`）一律换成通用中文文案，原始消息只进服务端日志。下文各接口括注的 code 均为枚举实际值。

### 邮箱预注册

```http
POST /api/user/emailregister
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `email` | `String` | 是 | 注册邮箱 |
| `password` | `String` | 是 | 初始密码 |
| `username` | `String` | 是 | 用户名 |

用法说明：

- 该接口会生成 6 位验证码并发送到邮箱。
- 注册信息临时写入 Redis（按场景隔离存储），有效期 5 分钟。
- 同一场景两次发码最小间隔 60 秒；单个验证码最多试错 5 次，超限即作废需重新获取。
- 下一步调用 `/api/user/register` 完成激活。

返回：`Result<String>`，成功 `data` 为 `"success"`。

### 激活注册

```http
POST /api/user/register
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `number` | `String` | 是 | 邮箱或手机号；邮箱注册时传邮箱 |
| `code` | `String` | 是 | 验证码 |

用法说明：

- 必须先调用 `/emailregister`。
- 验证码正确后真正插入用户表（昵称默认与用户名相同），验证码一码一用、校验成功即销毁。

返回：`Result<String>`。

### 登录

```http
POST /api/user/login
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | `String` | 是 | 用户名 |
| `password` | `String` | 是 | 密码 |

用法说明：

- 密码校验通过且未被封禁后登录成功，同时记录最后登录 IP（`X-Real-IP`）与登录时间。
- 登录成功后触发 api-governance 异步事件（`@AsyncAction("user-login")`），AFTER_SUCCESS 阶段向用户邮箱发送登录提醒邮件；被封禁用户登录时报错并附带解封时间。

返回：`Result<Map<String,Object>>`

`data` 结构：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `token` | `String` | JWT（subject 为 userId，含 `userName` 声明），后续请求放入 `Authorization: Bearer <token>` |
| `user` | `UserDto` | 当前用户信息 |

`UserDto` 结构（本文档多处返回该对象）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userId` | `String` | 用户 ID（数据库自增 BIGINT，DTO 中序列化为字符串） |
| `userName` | `String` | 用户名 |
| `email` | `String` | 邮箱 |
| `phone` | `String` | 手机号 |
| `bio` | `String` | 个人简介 |
| `nickName` | `String` | 昵称 |
| `avatarUrl` | `String` | 头像访问 URL |
| `birthday` | `LocalDate` | 生日 |
| `roleId` | `Integer` | 角色：0-root、1-普通用户、2-管理员 |
| `status` | `Integer` | 状态：0-禁用/封禁、1-启用、2-已注销 |
| `openId` | `String` | 第三方登录标识 |
| `totalSubmit` / `totalAc` | `Long` | 总提交数 / 总 AC 数 |
| `rating` | `Long` | 竞赛评分 |
| `createTime` / `updateTime` | `LocalDateTime` | 创建 / 更新时间 |
| `lastLoginIp` / `lastLoginTime` | `String` / `LocalDateTime` | 最后登录 IP / 时间 |
| `banTime` | `LocalDateTime` | 封禁截止时间 |

示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "jwt-token",
    "user": {
      "userId": "1",
      "userName": "alice"
    }
  }
}
```

### 修改当前用户密码

```http
POST /api/user/updatepassword
Authorization: Bearer <token>
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `oldPassword` | `String` | 是 | 旧密码 |
| `newPassword` | `String` | 是 | 新密码 |

用法说明：根据当前登录用户修改密码，旧密码必须正确。

返回：`Result<String>`。

### 找回密码-发送验证码

```http
POST /api/user/updatepasswordforemail
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `email` | `String` | 是 | 用户邮箱 |
| `password` | `String` | 否 | 已废弃，仅为兼容旧客户端保留，此步不再生效 |

用法说明：

- 会发送验证码到邮箱。
- 下一步调用 `/api/user/updatefromcode` 时再提交新密码。

返回：`Result<String>`。

### 找回密码-验证码确认

```http
POST /api/user/updatefromcode
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `number` | `String` | 是 | 邮箱或手机号 |
| `code` | `String` | 是 | 验证码 |
| `password` | `String` | 是 | 新密码，长度不能少于 6 位 |

返回：`Result<String>`。

### 邮箱密码登录

```http
POST /api/user/emaillogin
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `email` | `String` | 是 | 注册邮箱 |
| `password` | `String` | 是 | 密码 |

返回：`Result<Map<String,Object>>`，结构同 `/api/user/login`（`token` + `user`）。

### 获取邮箱登录验证码

```http
POST /api/user/getemailcode
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `email` | `String` | 是 | 注册邮箱 |

返回：`Result<String>`。

### 邮箱验证码登录

```http
POST /api/user/emailloginforcode
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `email` | `String` | 是 | 注册邮箱 |
| `code` | `String` | 是 | 通过 `/api/user/getemailcode` 获取的验证码 |

返回：`Result<Map<String,Object>>`，结构同 `/api/user/login`（`token` + `user`）。

### 查询当前用户信息

```http
GET /api/user/getuserbyid
Authorization: Bearer <token>
```

说明：不需要传任何参数，服务端从登录态（`UserContext`）读取当前用户 ID 并返回其信息。

返回：`Result<UserDto>`。

### 管理员查询任意用户信息

```http
GET /api/user/admin/user?userId=1
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | `Long` | 是 | 目标用户 ID |

说明：需要管理员身份（方法级 `@RequireAdmin` 校验）。

返回：`Result<UserDto>`。

### 管理员查询用户详情

```http
GET /api/user/admin/detail?userId=1
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | `Long` | 是 | 目标用户 ID |

说明：需要管理员身份（类级 `@RequireAdmin` 校验，`AdminUserController` 下所有接口均要求管理员）。

返回：`Result<UserDto>`。

### 管理员封禁用户

```http
POST /api/user/admin/ban
Authorization: Bearer <token>（管理员）
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | `Long` | 是 | 目标用户 ID |
| `banTime` | `Integer` | 是 | 封禁时长，单位天（`ban_time` 写为当前时间 + N 天） |

用法说明：

- 将用户 `status` 置 0（封禁），已注销（`status=2`）的用户不可被封禁。
- 封禁成功后向用户邮箱发送封禁通知邮件（经 RabbitMQ 委托 `service-message` 投递）。

返回：`Result<String>`，成功 `data` 为 `"success"`。

### 管理员解封用户

```http
POST /api/user/admin/unban
Authorization: Bearer <token>（管理员）
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userId` | `Long` | 是 | 目标用户 ID |

用法说明：仅对 `status=0` 的用户有效，否则报「该账号当前未被冻结，无需解冻」（`4090108`）；解封后 `status` 置 1，清空 `ban_time`、`ban_reason`，并发送解封通知邮件。

返回：`Result<String>`。

### 管理员查询被封禁用户信息

```http
GET /api/user/admin/ban/detail?userId=1
Authorization: Bearer <token>（管理员）
```

说明：返回 `Result<UserDto>`，但 `email`、`phone` 字段被置空（脱敏，不含敏感字段）。

### 管理员查询未处理申诉列表

```http
GET /api/user/admin/appeal/list
Authorization: Bearer <token>（管理员）
```

说明：返回 `Result<List<UserAppeal>>`，即 `user_appeal` 表中 `status=0`（未处理）的记录，按 `create_time` 倒序。字段：`userAppealId`、`userId`、`appealReason`、`reasonEmail`、`status`（0-未处理 1-已处理）、`createTime`、`updateTime`、`processResult`、`operatorId`。

### 管理员查询申诉详情

```http
GET /api/user/admin/appeal/detail?userAppealId=1
Authorization: Bearer <token>（管理员）
```

返回：`Result<AppealVo>`，`data` 结构：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userAppealId` | `Long` | 申诉记录 ID |
| `userId` | `Long` | 申诉人 ID |
| `appealReason` | `String` | 申诉理由 |
| `reasonEmail` | `String` | 接收补充材料的邮箱 |
| `banTime` | `LocalDateTime` | 申诉人封禁截止时间 |
| `banReason` | `String` | 申诉人冻结/封禁原因 |

### 管理员处理申诉

```http
POST /api/user/admin/appeal/process
Authorization: Bearer <token>（管理员）
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `userAppealId` | `Long` | 是 | 申诉记录 ID，必须处于未处理状态 |
| `processResult` | `String` | 是 | 处理结果说明，将作为邮件正文发送给申诉人 |
| `approve` | `boolean` | 是 | `true`-批准并解封用户（`status` 置 1，清空封禁信息），`false`-仅拒绝 |

用法说明：

- 更新申诉记录时带 `WHERE status=0` 条件（乐观锁），并发下重复处理会失败。
- 处理人 ID 取自 `UserContext`（当前管理员），处理后向申诉人邮箱发送「申诉处理结果」邮件。

返回：`Result<String>`。

### 修改当前用户昵称

```http
PUT /api/user/nickname?nickName=CodeWise
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `nickName` | `String` | 是 | 新昵称，不能为空，最长 30 个字符 |

返回：`Result<UserDto>`，`data` 为更新后的当前用户信息。

### 修改当前用户个人简介

```http
PUT /api/user/bio?bio=热爱算法
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `bio` | `String` | 是 | 个人简介，最长 500 个字符 |

返回：`Result<UserDto>`，`data` 为更新后的当前用户信息。

### 修改当前用户生日

```http
PUT /api/user/birthday?birthday=2000-01-01
Authorization: Bearer <token>
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `birthday` | `String` | 是 | 生日，格式 `yyyy-MM-dd`，不能晚于当前日期 |

返回：`Result<UserDto>`，`data` 为更新后的当前用户信息。

### 上传/更新头像

```http
POST /api/user/avatar
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

表单字段：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | `MultipartFile` | 是 | 图片文件 |

用法说明：

- 仅允许真实图片文件（`Content-Type` 须为 `image/*`，且 `ImageIO` 可解码）。
- 文件扩展名仅允许 `jpg`、`jpeg`、`png`、`gif`。
- 文件大小不能超过 5MB。
- 上传成功后更新当前用户头像，并尝试删除旧头像。
- 如果头像数据库更新失败，会自动删除本次新上传的文件，避免产生垃圾文件。

返回：`Result<String>`，`data` 为新头像的访问 URL（`/uploads/` 前缀）。

### 注销当前账号

```http
POST /api/user/delete
Authorization: Bearer <token>
```

说明：无需参数，仅可操作本人（用户 ID 取自 `UserContext`）。将 `status` 置 2（已注销），重复注销会报「该账号已注销，不支持此操作」（`4090107`）；数据不保留恢复入口，用户需重新注册。

返回：`Result<String>`，成功 `data` 为 `"success"`。

### 主动冻结当前账号

```http
POST /api/user/freeze
Authorization: Bearer <token>
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `banReason` | `String` | 是 | 冻结原因，不能为空 |
| `password` | `String` | 是 | 当前密码，用于验证身份 |

用法说明：

- 仅本人可操作；密码校验通过后 `status` 置 0（封禁态，冻结后无法登录），`ban_time` 写为当前时间 + 3600 天，并记录 `ban_reason`。
- 成功后发送冻结通知邮件；解冻需通过找回密码或联系管理员。

返回：`Result<String>`。

### 提交申诉（匿名）

```http
POST /api/user/appeal/submit
Content-Type: application/x-www-form-urlencoded
```

参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `phoneOrEmail` | `String` | 是 | 手机号或邮箱（含 `@` 按邮箱查账号，否则按手机号） |
| `appealReason` | `String` | 是 | 申诉理由，不能为空 |
| `reasonEmail` | `String` | 否 | 接收补充材料的邮箱 |
| `password` | `String` | 是 | 账号密码，用于验证身份 |

用法说明：

- 被封禁用户无法登录获取 token，故该接口为匿名接口，已在网关与身份拦截器白名单中放行。
- 仅允许 `status=0`（被封禁）的账号提交，否则报「当前账号未被封禁，无需提交申诉」（`4090104`）。
- 同一用户同时最多只有一条未处理申诉（`user_appeal` 表按 `user_id` + `status=0` 建唯一索引），重复提交报「您已提交过申诉，请耐心等待处理结果」（`4090106`）。

返回：`Result<String>`。

## 服务间内部接口

以下接口由 `service-api` 的 `UserFeignClient` 契约供其他服务经 OpenFeign 调用（内部调用同样经 `X-Internal-Token` 校验，不走用户 JWT）：

### 查询单个用户

```http
GET /api/user/info/{userId}
```

返回：`Result<UserDto>`（本服务的 `dto.UserDto`，结构见上文）。

### 批量查询用户

```http
POST /api/user/info/list
Content-Type: application/json
```

请求体：`List<Long>`（用户 ID 列表）。

返回：`Result<Map<Long, UserDto>>`，`data` 的 value 为 `service-api` 的 `dto.user.UserDto`（含 `userId`、`userName`、`nickName`、`avatarUrl`、`email`、`phone`、`birthday`、`createTime`、`totalSubmit`、`totalAc`、`rating`），key 为用户 ID。ID 不存在时对应项缺省，空列表返回空 Map。

## 消息队列（消费）

本服务不对外提供 MQ 相关 HTTP 接口，但作为消费者监听 `user.queue`（交换机 `user.exchange`，常量见 `service-common` 的 `MqContexts`）：

- 路由键 `user.judge.routing`（`JudgeResHandle`）：消费判题结果消息，按消息中的 `userId` 累加用户的 `total_submit`、`total_ac`。消费采用手动 ACK；更新失败或异常时拒绝并重新入队。

## 相关数据表

- `user`：用户主表（`user_id` 自增 BIGINT；`user_name`、`email`、`phone` 各有唯一索引；另有函数唯一索引 `uk_single_admin` 保证全局最多一个 `role_id=2` 管理员）。封禁相关字段：`status`、`ban_time`、`ban_reason`。
- `user_appeal`：用户申诉表（见上文申诉接口），字段 `user_appeal_id`、`user_id`、`appeal_reason`、`reason_email`、`status`、`process_result`、`operator_id`、`create_time`、`update_time`。
- `sys_init`：一次性初始化标记表（`init_key` 唯一索引 + `INSERT IGNORE` 保证幂等），当前用于 root 管理员账号的一次性引导创建（`codewise.bootstrap.root.*` 配置，`ROOT_PASSWORD` 未配置时跳过）。
