-- 手工迁移说明：将已有 codewise_user.user.email 从 VARCHAR(30) 扩至 VARCHAR(254)。
-- 此文件不由应用自动执行。部署前由运维在 MySQL 8 中手工运行，先备份并确认库名。
-- 建议先迁移数据库，再发布允许长邮箱的后端；DDL 可能等待元数据锁，请安排维护窗口。
-- 若现有 email 有自定义字符集、排序规则、默认值或注释，先查看 SHOW CREATE TABLE，
-- 并在 MODIFY 定义中保留这些属性；下述定义与本仓库基线表结构一致。
USE codewise_user;
SHOW CREATE TABLE `user`;

-- 仅扩大列宽：保留允许 NULL 的行为，不删除/重建 idx_email 唯一索引。
-- 不修改邮箱内容、不去空格、不改变大小写，也不改变索引使用的比较规则。
ALTER TABLE `user` MODIFY COLUMN email VARCHAR(254) NULL DEFAULT NULL;

-- 执行后确认 email 长度为 254、仍可空，且 idx_email 的 Non_unique 为 0。
SHOW COLUMNS FROM `user` LIKE 'email';
SHOW INDEX FROM `user` WHERE Key_name = 'idx_email';

-- 回滚前必须确认以下查询为 0，超长数据应先由业务方处理，禁止截断邮箱。
-- SELECT COUNT(*) FROM `user` WHERE CHAR_LENGTH(email) > 30;
-- ALTER TABLE `user` MODIFY COLUMN email VARCHAR(30) NULL DEFAULT NULL;
