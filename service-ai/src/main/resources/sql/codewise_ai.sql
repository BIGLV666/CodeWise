CREATE DATABASE IF NOT EXISTS codewise_ai
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE codewise_ai;

CREATE TABLE IF NOT EXISTS ai_conversation (
    conversation_id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_name VARCHAR(100) NOT NULL DEFAULT '新会话',
    message_id VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL,
    submit_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    question_content TEXT NOT NULL,
    code LONGTEXT NOT NULL,
    log TEXT NULL,
    input_data TEXT NULL,
    expected_output TEXT NULL,
    user_output TEXT NULL,
    status VARCHAR(20) NOT NULL,
    language VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id),
    UNIQUE KEY uk_ai_conversation_message (message_id),
    UNIQUE KEY uk_ai_conversation_user_question (user_id, question_id),
    KEY idx_ai_conversation_user_time (user_id, create_time)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS ai_message (
    message_id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    user_id BIGINT NOT NULL,
    current_code LONGTEXT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) DEFAULT NULL COMMENT 'ASSISTANT 消息生成状态：GENERATING/COMPLETED/FAILED/CANCELLED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (message_id),
    KEY idx_ai_message_conversation_cursor (conversation_id, message_id),
    CONSTRAINT fk_ai_message_conversation
        FOREIGN KEY (conversation_id)
        REFERENCES ai_conversation (conversation_id)
        ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS ai_conversation_memory (
    ai_conversation_memory_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    summary TEXT NOT NULL,
    summary_chars_count INT NOT NULL DEFAULT 0,
    end_message_id BIGINT NULL,
    version INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ai_conversation_memory_id),
    UNIQUE KEY uk_ai_memory_conversation_user (conversation_id, user_id),
    KEY idx_ai_memory_user_update (user_id, update_time),
    CONSTRAINT fk_ai_memory_conversation
        FOREIGN KEY (conversation_id)
        REFERENCES ai_conversation (conversation_id)
        ON DELETE CASCADE
) ENGINE = InnoDB;


CREATE TABLE IF NOT EXISTS user_ai_config (
    user_ai_config_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    group_name VARCHAR(64) NOT NULL,
    model_names JSON NOT NULL,
    ai_url VARCHAR(512) NOT NULL,
    api_key TEXT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_ai_config_id),
    UNIQUE KEY uk_user_ai_config_group (user_id, group_name),
    KEY idx_user_ai_config_user_update (user_id, update_time)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS `consumed_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_id` VARCHAR(64) NOT NULL COMMENT '全局唯一事件ID（信封eventId，兜底messageId）',
  `routing_key` VARCHAR(64) DEFAULT NULL COMMENT '来源路由键',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PROCESSING' COMMENT '状态：PROCESSING/COMPLETED/FAILED',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '已失败尝试次数',
  `last_error` VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败原因（截断）',
  `result_ref` VARCHAR(64) DEFAULT NULL COMMENT '业务结果引用（已生成的建议 ai_message 主键）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已消费事件幂等与状态表';
