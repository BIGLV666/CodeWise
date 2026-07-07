CREATE TABLE `question` (
    -- ========== 主键 ==========
                            `question_id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '题目ID',

    -- ========== 基本信息 ==========
                            `title` VARCHAR(200) NOT NULL COMMENT '题目标题',
                            `description` LONGTEXT NOT NULL COMMENT '题目描述',
                            `input_desc` TEXT COMMENT '输入描述',
                            `output_desc` TEXT COMMENT '输出描述',
                            `sample_input` TEXT COMMENT '样例输入',
                            `sample_output` TEXT COMMENT '样例输出',
                            `hint` TEXT COMMENT '提示',
                            `source` VARCHAR(100) DEFAULT NULL COMMENT '题目来源',
                            `content_hash` VARCHAR(32) not null  COMMENT '正文哈希',
    -- ========== 难度与标签 ==========
                            `difficulty` INTEGER DEFAULT 1 COMMENT '难度: 简单/中等/困难',
                            `tags` VARCHAR(255) DEFAULT NULL COMMENT '标签（逗号分隔）',

    -- ========== 时间/内存限制 ==========
                            `time_limit` INT DEFAULT 1000 COMMENT '时间限制(ms)',
                            `memory_limit` INT DEFAULT 256 COMMENT '内存限制(MB)',

    -- ========== 统计字段 ==========
                            `total_submit` BIGINT DEFAULT 0 COMMENT '总提交次数',
                            `total_ac` BIGINT DEFAULT 0 COMMENT '通过次数',
                            `pass_rate` DECIMAL(5,2) DEFAULT 0.00 COMMENT '通过率',

    -- ========== 状态与审计 ==========
                            `status` TINYINT DEFAULT 1 COMMENT '状态: 0-下架 1-上架 2-审核中',
                            `create_user_id` BIGINT NOT NULL COMMENT '创建人ID',
                            `create_time` DATETIME not null DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            `update_time` DATETIME not null DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                            `ai_statue` VARCHAR(20) not null DEFAULT 'pending' comment '待处理',

    -- ========== 索引 ==========
                            INDEX idx_difficulty (`difficulty`),
                            INDEX idx_status (`status`),
                            INDEX idx_create_time (`create_time`),
                            UNIQUE idx_id_hash(create_user_id,content_hash),
                            INDEX idx_ai_statue(ai_statue),
                            FULLTEXT idx_title (`title`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目表';
CREATE TABLE `test_case` (
    -- ========== 主键 ==========
                             `case_id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '测试用例ID',

    -- ========== 关联题目 ==========
                             `question_id` BIGINT NOT NULL COMMENT '题目ID',

    -- ========== 输入输出 ==========
                             `input_data` LONGTEXT COMMENT '输入数据（可能很大）',
                             `expected_output` LONGTEXT NOT NULL COMMENT '预期输出',

    -- ========== 类型标识 ==========
                             `is_sample` TINYINT DEFAULT 0 COMMENT '是否样例: 0-否 1-是（展示给用户）',
                             `is_hidden` TINYINT DEFAULT 1 COMMENT '是否隐藏: 0-否 1-是（判题用，不展示）',

    -- ========== 排序与权重 ==========
                             `sort_order` INT DEFAULT 0 COMMENT '执行顺序',
                             `score_weight` INT DEFAULT 100 COMMENT '分值权重（百分制）',

    -- ========== 超限配置（覆盖题目默认） ==========
                             `time_limit` INT DEFAULT NULL COMMENT '单用例时间限制(ms)',
                             `memory_limit` INT DEFAULT NULL COMMENT '单用例内存限制(MB)',

    -- ========== 审计字段 ==========
                             `create_user_id` BIGINT not null  COMMENT '创建用户',
                             `create_time` DATETIME not null DEFAULT  CURRENT_TIMESTAMP COMMENT '创建时间',
                             `update_time` DATETIME not null DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- ========== 索引 ==========
                             INDEX idx_question_id (`question_id`),
                             INDEX idx_is_sample (`is_sample`),
                             INDEX idx_sort_order (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试用例表';
create table `submit_record` (
    -- ========== 主键 ==========
    `submit_record_id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    -- ========== 关联题目 ==========
    `question_id` BIGINT NOT NULL COMMENT '题目ID',
    -- ========== 关联用户 ==========
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    -- ========== 提交时间 ==========
    `submit_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    -- ========== 提交内容 ==========
    `submit_content` LONGTEXT NOT NULL COMMENT '提交内容',
    -- ========== 提交状态 ==========
    `submit_status` VARCHAR(20)  COMMENT '提交状态'
    -- AC/WA/TLE/RE/CE/OLE/MLE/PE
    -- AC	Accepted	通过	代码完全正确，所有测试点都过了 ✅
    -- WA	Wrong Answer	答案错误	程序跑完了，但输出结果不对 ❌
    -- TLE	Time Limit Exceeded	超时	程序运行时间超过了题目限制 ⏰
    -- RE	Runtime Error	运行错误	程序运行时崩溃了（如数组越界、除零） 💥
    -- CE	Compile Error	编译错误	代码语法有问题，编译没通过 🔧
    -- MLE	Memory Limit Exceeded	内存超限	程序用的内存超过了题目限制 💾
    -- OLE	Output Limit Exceeded	输出超限	程序输出了太多内容（死循环打印） 📤
    -- PE	Presentation Error	格式错误	答案内容对，但多了/少了空格或换行 📐

    -- ========== 提交结果 ==========

    `time_used`   COMMENT '时间使用(ms)',
    `memory_used` INT  COMMENT '内存使用(MB)',
    `submit_scene` VARCHAR(10) not null default 'NORMAL' comment '判题来源，复习或者题目页',
    `judge_status` VARCHAR(20) NOT NULL COMMENT '判题状态',
    `language` VARCHAR(20) NOT NULL COMMENT '语言',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_question_id` (`question_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_submit_time` (`submit_time`),
    INDEX `idx_status` (`judge_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='记录表';

CREATE TABLE `judge_record` (
                                `judge_record_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '判题记录ID',
                                `submit_record_id` BIGINT NOT NULL COMMENT '提交记录ID',
                                `test_case_id` BIGINT NOT NULL COMMENT '测试用例ID',
                                `submit_status` VARCHAR(10) NOT NULL COMMENT '判题状态(AC/WA/TLE/RE/CE/...)',
                                `error_msg` VARCHAR(2000) DEFAULT NULL COMMENT '错误信息',
                                `log` LONGTEXT DEFAULT NULL COMMENT '日志',
                                `user_output` LONGTEXT DEFAULT NULL COMMENT '用户输出(actual)',
                                `test_total` INTEGER not null default 0 comment '测试样例总数',
                                `fail_index` INT DEFAULT 0 COMMENT '失败索引(从0开始)',
                                `code` LONGTEXT NOT NULL COMMENT '提交的代码',
                                `input_data` LONGTEXT NOT NULL COMMENT '测试用例输入',
                                `expected_output` LONGTEXT NOT NULL COMMENT '期望输出',
                                `time_used` INT DEFAULT 0 COMMENT '耗时(ms)',
                                `memory_used` INT DEFAULT 0 COMMENT '内存(KB)',
                                `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                PRIMARY KEY (`judge_record_id`),
                                INDEX `idx_submit_record` (`submit_record_id`),
                                INDEX `idx_test_case` (`test_case_id`),
                                INDEX `idx_submit_status` (`submit_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='判题记录表';