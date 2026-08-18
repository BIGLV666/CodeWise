# 社区审核与申诉功能实现总结

## 概述
本次实现为 CodeWise 平台社区模块添加了完整的**内容审核**和**申诉流程**，覆盖社区帖子（POST）、评论（COMMENT）、题解（SOLUTION）三种内容类型。

**实施日期**: 2026-08-18  
**涉及服务**: `service-community`, `service-message`, `service-api`, `service-common`

---

## 一、核心功能

### 1. 内容审核流程
- **管理员审核**: 通过 `PostCheckController` 对待审核内容进行审核
- **审核动作**: PASS（通过）、REJECT（拒绝）、TAKE_DOWN（下架）、RESTORE（恢复）
- **状态流转**: PENDING → APPROVED / REJECTED / TAKEN_DOWN
- **通知机制**: 审核结果通过 RabbitMQ 异步通知用户

### 2. 申诉流程
- **用户申诉**: 内容所有者可对被拒绝或下架的内容发起申诉（无限次）
- **管理员处理**: 可以通过（恢复内容）或拒绝申诉
- **双向通知**: 
  - 用户提交申诉 → 通知管理员
  - 管理员处理申诉 → 通知用户

### 3. 我的内容管理
- **统一查询**: 用户可查看自己的所有内容（帖子/评论/题解）
- **状态筛选**: 支持按状态（待审核/已通过/已拒绝/已下架）筛选
- **申诉入口**: 被拒绝/下架的内容可直接发起申诉

---

## 二、已完成工作

### 2.1 数据库变更

#### 新增表
- ✅ `appeal` - 申诉记录表
- ✅ `take_down_post` - 下架记录表

#### 修改现有表
- ✅ `post` 表新增 `check_reason` 字段
- ✅ `comment` 表新增 `check_reason` 字段  
- ✅ `solution` 表新增 `check_reason` 字段

所有 SQL 变更已更新到 `service-community/src/main/resources/sql.sql`

### 2.2 后端开发

#### service-community
- ✅ `PostCheckController` - 审核管理接口（管理员）
- ✅ `AppealController` - 用户申诉接口
- ✅ `AppealAdminController` - 管理员申诉处理接口
- ✅ `MyContentController` - 我的内容查询接口
- ✅ `PostCheckService` - 审核业务逻辑 + 通知发送
- ✅ `AppealService` - 申诉业务逻辑 + 通知发送
- ✅ `MyContentService` - 我的内容查询逻辑
- ✅ `AppealMapper` + XML - 申诉数据访问
- ✅ `TakeDownPostMapper` + XML - 下架记录数据访问
- ✅ 实体类、VO、DTO、枚举类

#### service-message
- ✅ `CheckedHandle` - 审核通知消费者
- ✅ `AppealHandle` - 申诉通知消费者

#### service-api
- ✅ `NotificationCheckedDto` - 审核通知数据结构
- ✅ `NotificationAppealDto` - 申诉通知数据结构
- ✅ `NotificationCenterType` 新增 `CHECKED`、`APPEAL` 类型
- ✅ `WebSocketQueueName` 新增对应队列名

#### service-common
- ✅ `MqContexts` 新增审核和申诉路由键常量
- ✅ `MqConfig` 新增队列绑定配置
- ✅ `RedisContext` 新增通知幂等性 Key 前缀

### 2.3 编译验证

所有模块编译通过：
```powershell
✅ service-common - clean install
✅ service-api - clean install  
✅ service-community - clean compile
✅ service-message - clean compile
```

### 2.4 文档

- ✅ API 接口文档: `docs/community/review-appeal-api.md`
- ✅ 实现总结文档: `docs/community/review-appeal-summary.md` (本文档)

---

## 三、技术架构

### 1. 模块划分

#### service-community（核心业务）
```
controller/
├── PostCheckController.java          # 审核 API（需管理员权限）
├── AppealController.java             # 用户申诉 API
├── AppealAdminController.java        # 管理员处理申诉 API
└── MyContentController.java          # 我的内容 API

service/
├── PostCheckService.java             # 审核逻辑 + 通知发送
├── AppealService.java                # 申诉逻辑 + 通知发送
└── MyContentService.java             # 我的内容查询

entry/
├── Appeal.java                       # 申诉实体（新表）
└── TakeDownPost.java                 # 下架记录实体（新表）

vo/
├── CheckDetailVo.java                # 审核详情（带作者信息）
├── AppealVo.java                     # 申诉详情
└── MyContentVo.java                  # 我的内容统一视图

enums/
└── PostStatus.java                   # 内容状态枚举
```

#### service-message（消息处理）
```
notificationcenter/handle/
├── CheckedHandle.java                # 监听审核通知队列
└── AppealHandle.java                 # 监听申诉通知队列
```

#### service-api（共享定义）
```
dto/notification/
├── NotificationCheckedDto.java       # 审核通知载荷
└── NotificationAppealDto.java        # 申诉通知载荷

enums/
├── NotificationCenterType.java       # 新增 CHECKED、APPEAL 类型
└── WebSocketQueueName.java           # 新增对应队列名
```

#### service-common（基础设施）
```
config/
├── MqContexts.java                   # 新增路由键常量
└── MqConfig.java                     # 新增队列绑定

RedisDto/
└── RedisContext.java                 # 新增幂等性 Key 前缀
```

---

### 2. 数据模型

#### Appeal（申诉表）
```java
appealId          BIGINT PRIMARY KEY    // 雪花ID
userId            BIGINT                // 申诉人
rootType          ENUM                  // POST/COMMENT/SOLUTION
rootId            BIGINT                // 内容ID
rootCommentId     BIGINT                // 评论ID（仅COMMENT类型）
questionId        BIGINT                // 关联题目ID（题解/评论）
reason            TEXT                  // 申诉理由
status            ENUM                  // PENDING/APPROVED/REJECTED
adminReason       TEXT                  // 管理员回复
handlerId         BIGINT                // 处理人ID
handledAt         DATETIME              // 处理时间
createTime        DATETIME
```

#### TakeDownPost（下架记录表）
```java
id                BIGINT PRIMARY KEY
rootType          ENUM
rootId            BIGINT
rootCommentId     BIGINT
questionId        BIGINT
reason            TEXT                  // 下架原因
adminId           BIGINT                // 操作管理员
createTime        DATETIME
```

#### Post/Comment/Solution（内容表修改）
新增字段：
- `status` ENUM: PENDING/APPROVED/REJECTED/TAKEN_DOWN
- `checkReason` TEXT: 审核不通过原因

---

### 3. API 设计

#### 审核 API（管理员专用）
```http
# 获取待审核列表
GET /api/community/check/posts?cursor=&size=20
GET /api/community/check/comments?cursor=&size=20
GET /api/community/check/solutions?cursor=&size=20

# 获取审核详情（带作者信息）
GET /api/community/check/detail?type=POST&id=123

# 执行审核
POST /api/community/check/posts/{id}
Body: { "action": "PASS/REJECT", "reason": "..." }

POST /api/community/check/comments/{id}
POST /api/community/check/solutions/{id}

# 下架/恢复内容
POST /api/community/check/posts/{id}/takedown
Body: { "reason": "违规原因" }

POST /api/community/check/posts/{id}/restore
```

#### 申诉 API（用户）
```http
# 提交申诉
POST /api/community/appeal
Body: {
  "rootType": "POST",
  "rootId": 123,
  "rootCommentId": null,
  "questionId": null,
  "reason": "我认为..."
}

# 查看我的申诉历史
GET /api/community/appeal/my?rootType=POST&rootId=123&cursor=&size=20

# 查看申诉详情
GET /api/community/appeal/{appealId}
```

#### 申诉管理 API（管理员）
```http
# 获取待处理申诉列表
GET /api/community/appeal/admin/pending?cursor=&size=20

# 处理申诉
POST /api/community/appeal/admin/{appealId}/handle
Body: {
  "pass": true,           // true=通过(恢复内容), false=拒绝
  "adminReason": "..."    // 处理说明
}
```

#### 我的内容 API（用户）
```http
# 查看我的所有内容
GET /api/community/my-content?status=PENDING&cursor=&size=20
# status可选: PENDING/APPROVED/REJECTED/TAKEN_DOWN
```

---

### 4. 消息通知流程

#### 审核通知（CheckedHandle）
```
PostCheckService
  └─> RabbitMQ (NOTIFICATION_EXCHANGE → NOTIFICATION_CHECKED_ROUTING_KEY)
        └─> CheckedHandle 消费
              └─> 生成通知文案
                    └─> 写入 notification_center 表
                          └─> WebSocket 推送（如果在线）
```

**通知文案规则:**
- PASS: "您的{类型}已通过审核"
- REJECT: "您的{类型}未通过审核：{原因}"
- TAKE_DOWN: "您的{类型}因违规已被下架：{原因}"
- RESTORE: "您的{类型}已恢复显示"

#### 申诉通知（AppealHandle）
```
AppealService (用户提交)
  └─> RabbitMQ (NOTIFICATION_EXCHANGE → NOTIFICATION_APPEAL_ROUTING_KEY)
        └─> AppealHandle 消费
              └─> userId=0 表示管理员通知
                    └─> "用户提交了申诉"

AppealService (管理员处理)
  └─> RabbitMQ
        └─> AppealHandle 消费
              └─> userId={申诉人} 表示用户通知
                    └─> "您的申诉已{通过/拒绝}"
```

---

## 四、关键设计决策

### 1. 统一的通知处理
**问题**: 原代码在 `NotificationDto` 中包含 `title` 和 `message`，导致业务逻辑分散

**解决方案**:
- `NotificationDto` 不再包含 `title/message`
- 由 MQ 消费者（CheckedHandle/AppealHandle）根据 `extraData` 生成文案
- 业务层只需发送结构化数据，文案逻辑集中在 message 服务

### 2. 幂等性保证
```java
// 每次操作生成唯一 messageId
String messageId = "check:" + postId + ":" + action;
String redisKey = RedisContext.NOTIFICATION_IDEMPOTENT_KEY + messageId;

// 7天内重复操作不会重复发送通知
Boolean set = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", 7, TimeUnit.DAYS);
if (Boolean.TRUE.equals(set)) {
    rabbitTemplate.convertAndSend(...);
}
```

### 3. 跨服务数据查询
审核详情需要显示作者信息，通过 OpenFeign 调用 `service-user`:
```java
CheckDetailVo detail = new CheckDetailVo();
detail.setContent(post.getContent());

try {
    Result<User> userResult = userFeignClient.getUserById(post.getUserId());
    detail.setAuthor(userResult.getData());
} catch (Exception e) {
    log.warn("获取用户信息失败: userId={}", post.getUserId(), e);
    detail.setAuthor(null); // 降级处理，不影响主流程
}
```

### 4. 申诉无限次设计
**需求**: Q2 提到"无限次申诉即可"

**实现**: 
- 不限制同一内容的申诉次数
- 每次提交生成新的 `Appeal` 记录
- 管理员可查看完整申诉历史

---

## 五、数据库变更（需执行）

### 新增表
```sql
-- 申诉表
CREATE TABLE `appeal` (
  `appeal_id` BIGINT PRIMARY KEY,
  `user_id` BIGINT NOT NULL,
  `root_type` ENUM('POST', 'COMMENT', 'SOLUTION') NOT NULL,
  `root_id` BIGINT NOT NULL,
  `root_comment_id` BIGINT,
  `question_id` BIGINT,
  `reason` TEXT NOT NULL,
  `status` ENUM('PENDING', 'APPROVED', 'REJECTED') DEFAULT 'PENDING',
  `admin_reason` TEXT,
  `handler_id` BIGINT,
  `handled_at` DATETIME,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_user` (`user_id`),
  INDEX `idx_root` (`root_type`, `root_id`),
  INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 下架记录表
CREATE TABLE `take_down_post` (
  `id` BIGINT PRIMARY KEY,
  `root_type` ENUM('POST', 'COMMENT', 'SOLUTION') NOT NULL,
  `root_id` BIGINT NOT NULL,
  `root_comment_id` BIGINT,
  `question_id` BIGINT,
  `reason` TEXT NOT NULL,
  `admin_id` BIGINT NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX `idx_root` (`root_type`, `root_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 修改现有表
```sql
-- Post 表
ALTER TABLE `post` 
  ADD COLUMN `status` ENUM('PENDING', 'APPROVED', 'REJECTED', 'TAKEN_DOWN') DEFAULT 'PENDING',
  ADD COLUMN `check_reason` TEXT;

-- Comment 表
ALTER TABLE `comment` 
  ADD COLUMN `status` ENUM('PENDING', 'APPROVED', 'REJECTED', 'TAKEN_DOWN') DEFAULT 'PENDING',
  ADD COLUMN `check_reason` TEXT;

-- Solution 表
ALTER TABLE `solution` 
  ADD COLUMN `status` ENUM('PENDING', 'APPROVED', 'REJECTED', 'TAKEN_DOWN') DEFAULT 'PENDING',
  ADD COLUMN `check_reason` TEXT;
```

---

## 六、编译验证

已验证以下模块编译通过：
```powershell
.\mvnw.cmd -f service-common\pom.xml clean install -DskipTests  # ✅
.\mvnw.cmd -f service-api\pom.xml clean install -DskipTests     # ✅
.\mvnw.cmd -f service-community\pom.xml clean compile -DskipTests # ✅
.\mvnw.cmd -f service-message\pom.xml clean compile -DskipTests   # ✅
```

---

## 七、待完成事项

### 1. 前端开发
**需确认前端代码位置**，然后实现以下页面：

#### 管理端页面
- [ ] 审核管理页（待审核列表 + 详情 + 操作）
- [ ] 申诉处理页（待处理申诉列表 + 详情 + 通过/拒绝）
- [ ] 下架内容管理页

#### 用户端页面
- [ ] 我的内容页（查看所有内容状态）
- [ ] 申诉页面（提交申诉 + 查看申诉历史）
- [ ] 通知中心（接收审核/申诉结果）

### 2. 权限配置
确认以下配置已添加：
- [ ] `service-gateway` 放行审核相关路径
- [ ] `@RequireAdmin` 注解生效（拦截器配置）

### 3. 测试
- [ ] 单元测试（Service 层）
- [ ] 集成测试（完整审核流程）
- [ ] MQ 消息消费测试
- [ ] 幂等性测试

---

## 七、待完成事项

### 7.1 前端开发
**需确认前端代码位置**，然后实现以下页面：

#### 管理端页面
- [ ] 审核管理页（待审核列表 + 详情 + 操作）
- [ ] 申诉处理页（待处理申诉列表 + 详情 + 通过/拒绝）
- [ ] 下架内容管理页（查看历史下架记录）

#### 用户端页面
- [ ] 我的内容页（查看所有内容状态）
- [ ] 申诉页面（提交申诉 + 查看申诉历史）
- [ ] 通知中心（接收审核/申诉结果通知）

### 7.2 权限配置
确认以下配置已添加：
- [ ] `service-gateway` 放行审核相关路径
- [ ] `@RequireAdmin` 注解生效（拦截器配置）

### 7.3 测试
- [ ] 单元测试（Service 层）
- [ ] 集成测试（完整审核流程 + 申诉流程）
- [ ] MQ 消息消费测试
- [ ] 幂等性测试（Redis Key 过期验证）
- [ ] 跨服务调用测试（Feign 降级处理）

### 7.4 部署前检查
- [ ] RabbitMQ 队列自动创建验证
- [ ] Redis 连接配置
- [ ] 数据库迁移脚本执行
- [ ] 环境变量配置（JWT_SECRET, CODEWISE_INTERNAL_TOKEN）

---

## 八、文件清单

### 新增文件（Untracked）
```
service-api/src/main/java/org/example/serviceapi/dto/notification/
├── NotificationAppealDto.java
└── NotificationCheckedDto.java

service-community/src/main/java/org/example/servicecommunity/
├── controller/
│   ├── PostCheckController.java
│   ├── AppealController.java
│   ├── AppealAdminController.java
│   └── MyContentController.java
├── service/
│   ├── PostCheckService.java
│   ├── AppealService.java
│   └── MyContentService.java
├── vo/
│   ├── CheckDetailVo.java
│   ├── AppealVo.java
│   └── MyContentVo.java
├── enums/
│   └── PostStatus.java
├── entry/
│   ├── Appeal.java
│   └── TakeDownPost.java
├── mapper/
│   └── AppealMapper.java
└── Dto/
    └── AppealDto.java

service-message/src/main/java/org/example/servicemessage/notificationcenter/handle/
├── AppealHandle.java
└── CheckedHandle.java
```

### 修改文件（Modified）
```
service-api/src/main/java/org/example/serviceapi/enums/
├── NotificationCenterType.java       # 新增 CHECKED、APPEAL
└── WebSocketQueueName.java           # 新增队列名

service-common/src/main/java/org/example/servicecommon/
├── RedisDto/RedisContext.java        # 新增幂等性Key
├── config/MqContexts.java            # 新增路由键
└── config/MqConfig.java              # 新增队列绑定
```

---

## 九、注意事项

1. **不要提交 application.yaml 的敏感配置**（已在 .gitignore 中）
2. **RabbitMQ 队列会自动创建**（通过 MqConfig 声明式配置）
3. **Redis Key 前缀已统一**（RedisContext 中管理）
4. **雪花ID 生成器已配置**（依赖 service-common）
5. **跨服务调用有熔断降级**（Feign 调用失败不影响主流程）
6. **幂等性保证**: 所有通知使用 Redis 实现 7 天幂等，避免重复发送
7. **评论即时发布**: 评论无待审核态，只能下架/恢复
8. **无限次申诉**: 用户可对同一内容多次申诉

---

## 十、后续优化建议

1. **审核规则引擎**: 目前是人工审核，可接入敏感词过滤/AI 自动审核
2. **审核日志**: 记录每次审核操作的管理员和时间戳（审计追踪）
3. **批量审核**: 支持管理员一次审核多条内容
4. **申诉超时自动关闭**: 例如 7 天未处理的申诉自动关闭
5. **审核统计**: 管理员审核效率统计、申诉通过率等数据看板
6. **内容评分系统**: 基于审核历史对用户/内容进行信用评分
7. **自动下架规则**: 多次违规自动下架，减少人工工作量

---

## 十一、API 快速参考

### 管理员审核接口
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/community/check/post/list` | GET | 待审核帖子列表 |
| `/api/community/check/post` | POST | 审核帖子（通过/拒绝） |
| `/api/community/check/post/status` | POST | 下架/恢复帖子 |
| `/api/community/check/comment/list` | GET | 待审核评论列表 |
| `/api/community/check/comment/status` | POST | 下架/恢复评论 |
| `/api/community/check/solution/list` | GET | 待审核题解列表 |
| `/api/community/check/solution` | POST | 审核题解（通过/拒绝） |
| `/api/community/check/solution/status` | POST | 下架/恢复题解 |
| `/api/community/check/takedown/list` | GET | 下架记录列表 |

### 申诉管理接口
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/community/appeal-admin/list` | GET | 申诉列表（可按状态筛选） |
| `/api/community/appeal-admin/handle` | POST | 处理申诉（通过/拒绝） |

### 用户接口
| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/community/appeal/submit` | POST | 提交申诉 |
| `/api/community/mine` | GET | 查看我的内容 |

详细 API 文档请参考: `docs/community/review-appeal-api.md`

---

## 十二、相关文档

- **API 接口文档**: `docs/community/review-appeal-api.md`
- **项目架构文档**: `docs/technical-design.md`
- **社区模块设计**: `docs/project-structure.md`
- **后端 API 总览**: `docs/backend-controller-api.md`

---

**实施状态**: ✅ 后端核心功能已完成并编译通过  
**待办事项**: 前端开发、权限配置、集成测试  
**会话交接**: 后端实现已完成，前端开发待确认代码位置后继续

**最后更新**: 2026-08-18  
**维护者**: CodeWise 后端团队
