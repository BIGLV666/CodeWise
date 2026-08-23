-- ai_message 增加生成状态列：ASSISTANT 消息的生命周期收尾
-- （GENERATING 生成中 / COMPLETED 完成 / FAILED 失败保留部分内容 / CANCELLED 超时或断开取消）。
USE codewise_ai;

ALTER TABLE ai_message
    ADD COLUMN status VARCHAR(20) DEFAULT NULL COMMENT 'ASSISTANT 消息生成状态：GENERATING/COMPLETED/FAILED/CANCELLED';

-- 存量回填：历史 ASSISTANT 消息均为生成完成后落库，统一置为 COMPLETED。
UPDATE ai_message SET status = 'COMPLETED' WHERE role = 'ASSISTANT';
