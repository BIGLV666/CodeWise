CREATE DATABASE IF NOT EXISTS codewise_ai
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE codewise_ai;

CREATE TABLE IF NOT EXISTS agent_conversation (
    agent_conversation_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Agent 会话主键',
    user_id BIGINT NOT NULL COMMENT 'CodeWise 用户 ID',
    agent_conversation_name VARCHAR(255) NOT NULL COMMENT '会话标题',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (agent_conversation_id),
    KEY idx_agent_conversation_user_update (user_id, update_time)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent 会话';

CREATE TABLE IF NOT EXISTS agent_message (
    agent_message_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Agent 消息主键',
    agent_conversation_id BIGINT NOT NULL COMMENT '所属 Agent 会话',
    user_id BIGINT NOT NULL COMMENT 'CodeWise 用户 ID，用于权限过滤',
    role VARCHAR(16) NOT NULL COMMENT 'SYSTEM、USER、ASSISTANT 或 TOOL',
    content TEXT NOT NULL COMMENT '消息内容',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (agent_message_id),
    KEY idx_agent_message_conversation_time (
        agent_conversation_id,
        create_time
    ),
    KEY idx_agent_message_user (user_id),
    CONSTRAINT fk_agent_message_conversation
        FOREIGN KEY (agent_conversation_id)
        REFERENCES agent_conversation (agent_conversation_id)
        ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent 消息';

-- 已弃用（2026-09）：摘要由 dsh 运行时的 compaction 接管（JSONL 会话日志 +
-- compaction-basic），本表保留仅为回滚兼容，新代码不再读写。
CREATE TABLE IF NOT EXISTS agent_memory (
    agent_memory_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '摘要主键',
    user_id BIGINT NOT NULL COMMENT 'CodeWise 用户 ID',
    agent_conversation_id BIGINT NOT NULL COMMENT '所属 Agent 会话',
    memory TEXT NOT NULL COMMENT '对话摘要',
    summarized_message_id BIGINT NOT NULL COMMENT '摘要覆盖到的消息 ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (agent_memory_id),
    UNIQUE KEY uk_agent_memory_conversation_user (
        agent_conversation_id,
        user_id
    ),
    KEY idx_agent_memory_user (user_id),
    CONSTRAINT fk_agent_memory_conversation
        FOREIGN KEY (agent_conversation_id)
        REFERENCES agent_conversation (agent_conversation_id)
        ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARACTER SET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent 对话摘要';
