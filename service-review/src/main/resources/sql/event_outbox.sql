-- 事务性 Outbox 事件表（复习提醒 -> 通知中心 消息可靠性）
-- 库：codewise_review（service-review 独立建表，结构与 codewise_question.event_outbox 同构）
-- 与业务写入同事务提交，由 service-common OutboxRelay 批量认领投递（FOR UPDATE SKIP LOCKED）
use codewise_review;
CREATE TABLE IF NOT EXISTS event_outbox (
  outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL COMMENT '事件唯一ID，消费幂等键',
  event_type VARCHAR(64) NOT NULL COMMENT '事件类型，如 REVIEW_REMINDER',
  exchange_name VARCHAR(128) NOT NULL COMMENT '目标交换机',
  routing_key VARCHAR(128) NOT NULL COMMENT '目标路由键',
  payload LONGTEXT NOT NULL COMMENT 'EventEnvelope JSON（大字段只放ID引用）',
  producer VARCHAR(64) NOT NULL COMMENT '生产者服务名',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/DEAD',
  retry_count INT NOT NULL DEFAULT 0 COMMENT '投递重试次数',
  next_retry_time DATETIME NULL COMMENT '下次投递时间（指数退避）',
  last_error VARCHAR(2000) NULL COMMENT '最近一次投递失败原因',
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_time DATETIME NULL,
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_outbox_status_next (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务性Outbox事件表';
