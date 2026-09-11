# service-user 用户服务接口

本文档整理用户服务的公开 HTTP 接口。统一鉴权和响应约定见 [`../backend-controller-api.md`](../backend-controller-api.md)。

基础路径：`/api/user`

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

- 该接口会生成验证码并发送到邮箱。
- 注册信息临时写入 Redis，有效期约 5 分钟。
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
- 验证码正确后真正插入用户表。

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

返回：`Result<Map<String,Object>>`

`data` 结构：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `token` | `String` | JWT，后续请求放入 `Authorization: Bearer <token>` |
| `user` | `UserDto` | 当前用户信息 |

示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "jwt-token",
    "user": {
      "userId": 1,
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
| `password` | `String` | 是 | 新密码 |

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

- 仅允许真实图片文件。
- 文件扩展名仅允许 `jpg`、`jpeg`、`png`、`gif`。
- 文件大小不能超过 5MB。
- 上传成功后更新当前用户头像，并尝试删除旧头像。
- 如果头像数据库更新失败，会自动删除本次新上传的文件，避免产生垃圾文件。

返回：`Result<String>`。
