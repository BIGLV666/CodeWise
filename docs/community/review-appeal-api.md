# 社区内容审核与申诉功能 API 文档

## 概述

本文档描述 CodeWise 平台社区模块的内容审核和申诉功能 API，涵盖帖子（POST）、评论（COMMENT）、题解（SOLUTION）三种内容类型。

**服务**: `service-community` (端口 8087)  
**网关路由**: 所有请求通过 `service-gateway` (8082) 转发，需携带 JWT Token

---

## 一、内容审核 API（管理员专用）

**权限要求**: 所有审核接口需要 `@RequireAdmin` 权限  
**基础路径**: `/api/community/check`

### 1.1 获取待审核帖子列表

```http
GET /api/community/check/post/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标，上一页最后一条的 post_id |
| pageSize | Integer | 否 | 每页数量，默认 10 |

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "postId": 123456,
        "postTitle": "如何优化算法性能",
        "postContent": "我在做题时...",
        "userId": 1001,
        "userName": "张三",
        "status": 0,
        "createTime": "2026-08-18T10:30:00"
      }
    ],
    "hasMore": true,
    "total": 25
  }
}
```

### 1.2 获取帖子审核详情

```http
GET /api/community/check/post/detail?postId=123456
```

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "content": {
      "postId": 123456,
      "postTitle": "标题",
      "postContent": "正文...",
      "status": 0,
      "createTime": "2026-08-18T10:30:00"
    },
    "author": {
      "userId": 1001,
      "userName": "张三",
      "avatar": "https://..."
    }
  }
}
```

### 1.3 审核待审核帖子

```http
POST /api/community/check/post?postId=123456&status=1
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| postId | Long | 是 | 帖子 ID |
| status | Integer | 是 | 1=通过，2=拒绝 |

**响应**:
```json
{
  "code": 200,
  "data": null
}
```

### 1.4 下架或恢复已发布帖子

```http
POST /api/community/check/post/status
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| postId | Long | 是 | 帖子 ID |
| status | Integer | 是 | 1=恢复，2=下架 |
| reason | String | 否 | 下架原因（下架时建议填写） |

**响应**:
```json
{
  "code": 200,
  "data": null
}
```

---

### 1.5 获取待审核评论列表

```http
GET /api/community/check/comment/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标 |
| pageSize | Integer | 否 | 每页数量，默认 10 |
| rootCommentId | Long | 否 | 筛选某个一级评论下的回复 |
| type | PostType | 否 | POST 或 SOLUTION |

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "commentId": 789,
        "comment": "评论内容...",
        "userId": 1002,
        "userName": "李四",
        "postId": 123456,
        "rootCommentId": null,
        "status": 0,
        "type": "POST",
        "createTime": "2026-08-18T11:00:00"
      }
    ],
    "hasMore": false,
    "total": 8
  }
}
```

### 1.6 获取评论审核详情

```http
GET /api/community/check/comment/detail?commentId=789
```

### 1.7 下架或恢复评论

```http
POST /api/community/check/comment/status
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| commentId | Long | 是 | 评论 ID |
| status | Integer | 是 | 1=恢复，2=下架 |
| reason | String | 否 | 下架原因 |

**说明**: 评论采用即时发布，无待审核态，因此没有「审核通过/拒绝」接口。

---

### 1.8 获取待审核题解列表

```http
GET /api/community/check/solution/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标 |
| pageSize | Integer | 否 | 每页数量，默认 10 |

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "solutionId": 5001,
        "questionId": 101,
        "solutionTitle": "双指针解法",
        "solutionContent": "这道题可以用...",
        "solutionUserId": 1003,
        "status": 0,
        "createTime": "2026-08-18T12:00:00"
      }
    ],
    "hasMore": true,
    "total": 15
  }
}
```

### 1.9 获取题解审核详情

```http
GET /api/community/check/solution/detail?solutionId=5001
```

### 1.10 审核待审核题解

```http
POST /api/community/check/solution?solutionId=5001&status=1
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| solutionId | Long | 是 | 题解 ID |
| status | Integer | 是 | 1=通过，2=拒绝 |

### 1.11 下架或恢复已发布题解

```http
POST /api/community/check/solution/status
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| solutionId | Long | 是 | 题解 ID |
| status | Integer | 是 | 1=恢复，2=下架 |
| reason | String | 否 | 下架原因 |

---

### 1.12 通用审核详情查询

```http
GET /api/community/check/detail?type=POST&targetId=123456
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | PostType | 是 | POST/COMMENT/SOLUTION |
| targetId | Long | 是 | 对应内容的 ID |

**响应**: 同各类型的详情接口

---

### 1.13 获取下架记录列表

```http
GET /api/community/check/takedown/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标 |
| pageSize | Integer | 否 | 每页数量，默认 10 |

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "id": 9001,
        "rootType": "POST",
        "rootId": 123456,
        "rootCommentId": null,
        "questionId": null,
        "reason": "涉及敏感内容",
        "adminId": 1,
        "createTime": "2026-08-18T14:00:00"
      }
    ],
    "hasMore": false,
    "total": 3
  }
}
```

---

## 二、申诉管理 API（管理员专用）

**权限要求**: `@RequireAdmin`  
**基础路径**: `/api/community/appeal-admin`（也可通过 `/api/community/check/appeal` 访问）

### 2.1 获取申诉列表

```http
GET /api/community/appeal-admin/list
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lastId | Long | 否 | 游标，上一页最后一条的 appeal_id |
| pageSize | Integer | 否 | 每页数量，默认 20 |
| status | Integer | 否 | 筛选状态：0=待处理，1=已拒绝，2=已恢复，null=全部 |

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "appealId": 2001,
        "userId": 1001,
        "rootType": "POST",
        "rootId": 123456,
        "rootCommentId": null,
        "questionId": null,
        "reason": "我认为内容没有违规...",
        "status": 0,
        "adminReason": null,
        "handlerId": null,
        "handledAt": null,
        "createTime": "2026-08-18T15:00:00"
      }
    ],
    "hasMore": true,
    "total": 12
  }
}
```

### 2.2 处理申诉

```http
POST /api/community/appeal-admin/handle
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| appealId | Long | 是 | 申诉 ID |
| pass | Boolean | 是 | true=通过并恢复内容，false=拒绝申诉 |
| adminReason | String | 否 | 管理员回复说明 |

**响应**:
```json
{
  "code": 200,
  "data": null
}
```

**业务逻辑**:
- `pass=true`: 申诉状态改为 `APPROVED(2)`，原内容状态恢复为 `APPROVED(1)`
- `pass=false`: 申诉状态改为 `REJECTED(1)`，原内容状态保持不变

---

## 三、用户申诉 API

**基础路径**: `/api/community/appeal`

### 3.1 提交申诉

```http
POST /api/community/appeal/submit
```

**请求体**:
```json
{
  "rootType": "POST",
  "rootId": 123456,
  "rootCommentId": null,
  "questionId": null,
  "reason": "我认为我的帖子没有违规，请重新审核..."
}
```

**字段说明**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| rootType | String | 是 | POST/COMMENT/SOLUTION |
| rootId | Long | 是 | 内容 ID（帖子/评论/题解的 ID） |
| rootCommentId | Long | 否 | 如果是评论，填写 comment_id |
| questionId | Long | 否 | 关联题目 ID（题解时使用） |
| reason | String | 是 | 申诉理由 |

**响应**:
```json
{
  "code": 200,
  "data": null
}
```

**说明**: 
- 同一内容可无限次申诉，每次生成新的 `Appeal` 记录
- 申诉成功后，系统会通知管理员

---

## 四、我的内容 API（用户）

**基础路径**: `/api/community/mine`

### 4.1 查看我的内容

```http
GET /api/community/mine?type=POST&lastId=&pageSize=20
```

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | PostType | 是 | POST/COMMENT/SOLUTION |
| lastId | Long | 否 | 游标 |
| pageSize | Integer | 否 | 每页数量，默认 20 |

**响应示例**:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "id": 123456,
        "type": "POST",
        "title": "如何优化算法性能",
        "content": "我在做题时...",
        "status": 1,
        "checkReason": null,
        "createTime": "2026-08-18T10:30:00"
      },
      {
        "id": 123457,
        "type": "POST",
        "title": "另一个帖子",
        "content": "...",
        "status": 2,
        "checkReason": "内容涉及违规",
        "createTime": "2026-08-17T09:00:00"
      }
    ],
    "hasMore": true,
    "total": 45
  }
}
```

**状态码说明**:
| status | 说明 |
|--------|------|
| 0 | 待审核 |
| 1 | 已通过/正常 |
| 2 | 已拒绝/已下架 |

---

## 五、数据模型

### 5.1 内容状态枚举 (PostStatus)

```java
public enum PostStatus {
    PENDING(0, "待审核"),
    APPROVED(1, "已通过"),
    REJECTED(2, "已拒绝/已下架");
}
```

### 5.2 内容类型枚举 (PostType)

```java
public enum PostType {
    POST,       // 社区帖子
    COMMENT,    // 评论
    SOLUTION    // 题解
}
```

### 5.3 申诉状态枚举 (AppealStatus)

```java
public enum AppealStatus {
    PENDING(0, "待处理"),
    REJECTED(1, "已拒绝"),
    APPROVED(2, "已通过/已恢复")
}
```

---

## 六、通知机制

### 6.1 审核通知

**触发场景**: 管理员审核内容后（通过/拒绝/下架/恢复）

**通知对象**: 内容所有者

**通知内容**:
- PASS: "您的{类型}已通过审核"
- REJECT: "您的{类型}未通过审核：{原因}"
- TAKE_DOWN: "您的{类型}因违规已被下架：{原因}"
- RESTORE: "您的{类型}已恢复显示"

**通知类型**: `NotificationCenterType.CHECKED`

**消息队列**: `NOTIFICATION_CHECKED_ROUTING_KEY`

### 6.2 申诉通知

#### 用户提交申诉 → 通知管理员

**通知对象**: 管理员（userId=0）

**通知内容**: "用户{userName}提交了申诉"

#### 管理员处理申诉 → 通知用户

**通知对象**: 申诉人

**通知内容**:
- 通过: "您的申诉已通过，内容已恢复"
- 拒绝: "您的申诉已被拒绝：{管理员回复}"

**通知类型**: `NotificationCenterType.APPEAL`

**消息队列**: `NOTIFICATION_APPEAL_ROUTING_KEY`

---

## 七、数据库表结构

### 7.1 appeal（申诉表）

```sql
CREATE TABLE appeal(
    appeal_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    post_type VARCHAR(20) NOT NULL,
    user_id BIGINT NOT NULL,
    reason TEXT,
    take_down_reason TEXT,
    status INT NOT NULL,
    admin_user_id BIGINT,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_post_id_status(user_id,post_id,post_type,status),
    INDEX idx_post_id(post_id,user_id,post_type,status),
    INDEX idx_type_status(post_type,status)
);
```

### 7.2 take_down_post（下架记录表）

```sql
CREATE TABLE take_down_post(
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    root_type VARCHAR(20) NOT NULL COMMENT '内容类型：POST/SOLUTION/COMMENT',
    root_id BIGINT NOT NULL COMMENT '根内容ID（帖子/题解/评论的ID）',
    root_comment_id BIGINT COMMENT '如果是评论，记录评论ID',
    question_id BIGINT COMMENT '关联题目ID（题解时使用）',
    reason TEXT NOT NULL COMMENT '下架原因',
    admin_id BIGINT NOT NULL COMMENT '操作管理员ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_root(root_type, root_id),
    INDEX idx_admin(admin_id),
    INDEX idx_create_time(create_time)
);
```

### 7.3 post/comment/solution 表新增字段

```sql
-- Post 表
ALTER TABLE `post` 
  ADD COLUMN `check_reason` TEXT COMMENT '审核/下架原因';

-- Comment 表
ALTER TABLE `comment` 
  ADD COLUMN `check_reason` TEXT COMMENT '审核/下架原因';

-- Solution 表
ALTER TABLE `solution` 
  ADD COLUMN `check_reason` TEXT COMMENT '审核/下架原因';
```

---

## 八、权限要求

### 8.1 管理员权限

以下接口需要 `@RequireAdmin` 权限（通过 JWT 中的 `role` 字段判断）：

- 所有 `/api/community/check/**` 路径
- 所有 `/api/community/appeal-admin/**` 路径

### 8.2 用户权限

以下接口需要普通登录用户权限（JWT 验证）：

- `/api/community/appeal/submit` - 提交申诉
- `/api/community/mine` - 查看我的内容

**身份来源**: 从 `UserContext.getUserId()` 获取，不接受客户端传递的 userId 参数

---

## 九、错误码

| code | 说明 |
|------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未登录或 Token 无效 |
| 403 | 无权限（非管理员） |
| 404 | 内容不存在 |
| 500 | 服务器内部错误 |

**错误响应示例**:
```json
{
  "code": 403,
  "message": "需要管理员权限",
  "data": null
}
```

---

## 十、使用示例

### 10.1 管理员审核帖子流程

1. 获取待审核帖子列表
```bash
curl -X GET "http://localhost:8082/api/community/check/post/list?pageSize=10" \
  -H "Authorization: Bearer {admin_token}"
```

2. 查看帖子详情
```bash
curl -X GET "http://localhost:8082/api/community/check/post/detail?postId=123456" \
  -H "Authorization: Bearer {admin_token}"
```

3. 审核通过
```bash
curl -X POST "http://localhost:8082/api/community/check/post?postId=123456&status=1" \
  -H "Authorization: Bearer {admin_token}"
```

### 10.2 用户申诉流程

1. 查看我的内容（发现被拒绝）
```bash
curl -X GET "http://localhost:8082/api/community/mine?type=POST" \
  -H "Authorization: Bearer {user_token}"
```

2. 提交申诉
```bash
curl -X POST "http://localhost:8082/api/community/appeal/submit" \
  -H "Authorization: Bearer {user_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "rootType": "POST",
    "rootId": 123456,
    "reason": "我认为内容没有违规..."
  }'
```

3. 等待管理员处理（会收到通知）

---

## 十一、注意事项

1. **幂等性保证**: 所有通知消息使用 Redis 实现 7 天幂等，相同操作不会重复发送通知
2. **降级处理**: 审核详情中的作者信息通过 Feign 调用获取，调用失败不影响主流程
3. **无限次申诉**: 用户可对同一内容多次申诉，每次生成新记录
4. **评论即时发布**: 评论无待审核态，只能下架/恢复，不能审核通过/拒绝
5. **跨服务调用**: 审核相关操作会触发 RabbitMQ 消息，由 `service-message` 异步处理通知
6. **下架记录**: 所有下架操作都会在 `take_down_post` 表中留存记录，用于审计和统计

---

**文档版本**: v1.0  
**最后更新**: 2026-08-18  
**维护者**: CodeWise 后端团队
