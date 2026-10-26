-- 存量库升级：小组提交判题索引表。
-- 新环境由 question.sql 直接建表；已有环境手工执行本脚本（幂等）。
-- 若已按旧版脚本建表（索引名为 idx_group_submit_group_time），需手工替换索引：
--   ALTER TABLE group_submit_record
--     DROP INDEX idx_group_submit_group_time,
--     ADD INDEX idx_group_submit_group_cursor (group_id, group_submit_record_id);
CREATE TABLE IF NOT EXISTS `group_submit_record` (
    `group_submit_record_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '小组提交记录ID',
    `submit_record_id` BIGINT NOT NULL COMMENT '提交记录ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `group_id` BIGINT NOT NULL COMMENT '小组ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`group_submit_record_id`),
    -- 一条判题提交最多归属一个小组，重复写索引视为数据异常
    UNIQUE KEY `uk_group_submit_submit` (`submit_record_id`),
    -- 游标分页：WHERE group_id=? AND group_submit_record_id<? ORDER BY group_submit_record_id DESC
    KEY `idx_group_submit_group_cursor` (`group_id`, `group_submit_record_id`),
    KEY `idx_group_submit_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='小组提交记录索引表（记录不可修改/删除）';
