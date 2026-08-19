
-- 失败提交记录表
CREATE TABLE IF NOT EXISTS `failure_submit` (
    `failure_submit_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '失败提交ID，主键',
    `submit_record_id` BIGINT NOT NULL COMMENT '提交记录ID，关联submit_record表',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-待处理，1-重试中，2-成功，3-失败',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    `last_error` TEXT NULL DEFAULT NULL COMMENT '上次重试的错误日志',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `retry_time` DATETIME NULL DEFAULT NULL COMMENT '最后重试时间',
    PRIMARY KEY (`failure_submit_id`),
    UNIQUE key `uk_submit_record_id` (`submit_record_id`),
    INDEX `idx_status_retry` (`status`, `retry_time`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='失败提交记录表';


