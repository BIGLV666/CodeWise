create table user(
    user_id BIGINT AUTO_INCREMENT primary key ,
    user_name VARCHAR(30) NOT NULL ,
    password VARCHAR(64) NOT NULL ,
    email VARCHAR(30),
    phone VARCHAR(20) ,
    bio TEXT ,
    nick_name VARCHAR(30) NOT NULL ,
    avatar_url VARCHAR(100),
    birthday DATE ,
    role_id INTEGER DEFAULT 1 ,
    status INTEGER DEFAULT 1,

    open_id VARCHAR(100) ,
    total_submit BIGINT DEFAULT 0,
    total_ac BIGINT DEFAULT 0,
    rating BIGINT DEFAULT 0,

    create_time DATETIME DEFAULT (CURRENT_TIMESTAMP) ,
    update_time DATETIME DEFAULT (CURRENT_TIMESTAMP),

    last_login_ip VARCHAR(100) not null ,
    last_login_time DATETIME not null ,
    ban_time DATETIME,
    ban_reason VARCHAR(255) ,
    UNIQUE INDEX idx_username (user_name),
    UNIQUE INDEX idx_email (email),
    UNIQUE INDEX idx_phone(phone)


);

-- 一次性初始化标记表：保证 root 等初始化项只执行一次。
-- init_key 唯一索引 + INSERT IGNORE 提供并发/重启下的幂等保证。
-- 该表也可由服务启动时自建（见 SysInitMapper#ensureTable），此处仅作文档参考。
CREATE TABLE IF NOT EXISTS sys_init (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    init_key VARCHAR(64) NOT NULL,
    created_at DATETIME DEFAULT (CURRENT_TIMESTAMP),
    UNIQUE KEY uk_init_key (init_key)
);

-- 双保险：保证全局最多只有一个管理员（role_id=2）。
-- MySQL 8 支持函数索引，role_id 非 2 时表达式为 NULL，不占用索引条目。
CREATE UNIQUE INDEX uk_single_admin ON user ((CASE WHEN role_id = 2 THEN role_id END));

-- 用户申诉表：被封禁/冻结的用户提交申诉，管理员处理后回填结果。
create table user_appeal(
    user_appeal_id BIGINT AUTO_INCREMENT primary key ,

    -- 申诉人 id，关联 user.user_id
    user_id BIGINT NOT NULL ,
    -- 申诉原因
    appeal_reason VARCHAR(500) NOT NULL ,
    -- 接收补充材料的邮箱
    reason_email VARCHAR(100) ,

    -- 处理状态：0-未处理 1-已处理
    status INTEGER NOT NULL DEFAULT 0 ,
    -- 处理结果说明，处理完成时填写
    process_result VARCHAR(500) ,
    -- 处理人 id，关联 user.user_id，未处理时为空
    operator_id BIGINT ,

    create_time DATETIME DEFAULT (CURRENT_TIMESTAMP) ,
    update_time DATETIME DEFAULT (CURRENT_TIMESTAMP) ON UPDATE CURRENT_TIMESTAMP ,

    INDEX idx_appeal_user (user_id),
    INDEX idx_appeal_status (status),
    INDEX idx_appeal_operator (operator_id)
);

-- 同一用户同时最多只有一条「未处理」申诉（status=0 时按 user_id 唯一），
-- 已处理的申诉不占索引条目，历史申诉条数不受限。
-- 插入重复未处理申诉时抛 DuplicateKeyException，与 Service 提示「请耐心等待处理」对应。
CREATE UNIQUE INDEX uk_pending_appeal ON user_appeal ((CASE WHEN status = 0 THEN user_id END));



