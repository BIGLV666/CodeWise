-- service-quiz-nest（Quiz/小组微服务）建表脚本
-- 注意：group_type / role / join_status 三个枚举列按 MyBatis-Plus 默认枚举处理器落库为
-- 枚举的 name()（见 com.example.servicequiznest.enums 下各枚举），因此用 VARCHAR 而非 INT。

CREATE DATABASE IF NOT EXISTS `codewise_quiz_nest`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `codewise_quiz_nest`;

-- 小组：创建人同时写入一条 root 成员记录；group_type 决定加入方式
CREATE TABLE IF NOT EXISTS `group` (
    `group_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '小组ID',
    `group_name` VARCHAR(255) NOT NULL COMMENT '小组名称',
    `user_id` BIGINT NOT NULL COMMENT '创建人ID（对应 root 成员）',
    `group_description` VARCHAR(500) DEFAULT NULL COMMENT '小组简介，可空',
    `group_type` VARCHAR(20) NOT NULL DEFAULT 'APPROVAL' COMMENT '加入方式：FREE 公开 / APPROVAL 审批 / INVITE 邀请',
    `member_count` BIGINT NOT NULL DEFAULT 0 COMMENT '成员数（APPROVED 成员数，含 root），随成员增删维护',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`group_id`),
    KEY `idx_group_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '小组';

-- 小组成员：user_id + group_id 唯一，一个用户在一个小组里只有一条身份记录
-- （申请时先以 PENDING 占位防重复申请，审批通过后置为 APPROVED）
CREATE TABLE IF NOT EXISTS `group_member` (
    `group_member_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '成员记录ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `group_id` BIGINT NOT NULL COMMENT '小组ID',
    `role` VARCHAR(20) NOT NULL COMMENT '成员角色：MEMBER 普通成员 / ADMIN 管理员 / ROOT 创建人',
    `join_status` VARCHAR(20) NOT NULL COMMENT '加入状态：PENDING 待审批 / APPROVED 已加入 / REJECTED 已拒绝',
    `join_time` DATETIME DEFAULT NULL COMMENT '加入时间，审批通过时写入',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`group_member_id`),
    UNIQUE KEY `uk_group_member_user_group` (`user_id`, `group_id`),
    KEY `idx_group_member_group_status` (`group_id`, `join_status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '小组成员';

-- 小组题库：只存题目索引，题目正文在 service-question
-- group_id + question_id 唯一：同一道题在同一个小组里只能出现一次
-- is_plat_from_question：1 引用平台题目（同步只写索引）/ 0 小组自建（先同步建题再回写 question_id）
CREATE TABLE IF NOT EXISTS `group_question` (
    `group_question_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '小组题库记录ID',
    `question_id` BIGINT NOT NULL COMMENT '题目ID（service-question 的 question.question_id）',
    `create_user_id` BIGINT NOT NULL COMMENT '添加人ID',
    `group_id` BIGINT NOT NULL COMMENT '小组ID',
    `is_plat_from_question` TINYINT NOT NULL DEFAULT 0 COMMENT '是否平台题目：1 平台题目 / 0 小组自建',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`group_question_id`),
    UNIQUE KEY `uk_group_question_group_question` (`group_id`, `question_id`),
    KEY `idx_group_question_question` (`question_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '小组题库（题目索引）';
