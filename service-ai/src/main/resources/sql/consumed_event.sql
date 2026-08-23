USE codewise_ai;

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
