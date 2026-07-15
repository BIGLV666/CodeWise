CREATE DATABASE IF NOT EXISTS codewise_message
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE codewise_message;

CREATE TABLE IF NOT EXISTS notification_center (
    notification_center_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT COMMENT '通知ID',
    message_id VARCHAR(64) NOT NULL COMMENT '消息唯一ID，用于消费幂等',
    title VARCHAR(255) NOT NULL COMMENT '通知标题',
    content TEXT NOT NULL COMMENT '通知正文',
    type VARCHAR(32) NOT NULL COMMENT '通知类型：LIKE、REVIEW',
    business_type VARCHAR(32) NOT NULL COMMENT '业务类型：POST、COMMENT、SOLUTION、REVIEW_RECORD',
    business_id BIGINT DEFAULT NULL COMMENT '关联业务ID',
    user_id BIGINT NOT NULL COMMENT '通知接收用户ID',
    is_read TINYINT NOT NULL DEFAULT 0 COMMENT '0未读，1已读',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '0正常，1删除',
    extra_data JSON DEFAULT NULL COMMENT '扩展数据',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_message_id (message_id),
    INDEX idx_user_status_time (user_id, is_deleted, is_read, create_time),
    INDEX idx_business (business_type, business_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户通知中心';
