CREATE TABLE `function_config` (
    `function_config_id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '函数配置ID',
    `question_id` BIGINT NOT NULL COMMENT '题目ID',
    `class_name` VARCHAR(100) NOT NULL DEFAULT 'Solution' COMMENT '提交类名',
    `method_name` VARCHAR(100) NOT NULL COMMENT '函数名',
    `parameter_config` JSON NOT NULL COMMENT '参数类型和名称配置',
    `output_type` VARCHAR(100) NOT NULL COMMENT '返回值类型',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_function_config_question` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='函数题配置表';

CREATE TABLE `function_test_case` (
    `function_test_case_id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '函数测试用例ID',
    `question_id` BIGINT NOT NULL COMMENT '题目ID',
    `input_data` LONGTEXT NOT NULL COMMENT '函数参数，多个参数按换行分隔',
    `expected_output` LONGTEXT NOT NULL COMMENT '预期返回值',
    `case_hash` CHAR(64) GENERATED ALWAYS AS (
        SHA2(CONCAT(`input_data`, CHAR(0), `expected_output`), 256)
    ) STORED COMMENT '输入输出 SHA-256 哈希',
    `is_sample` TINYINT NOT NULL DEFAULT 0 COMMENT '是否为公开样例',
    `is_hidden` TINYINT NOT NULL DEFAULT 1 COMMENT '是否隐藏',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '执行顺序',
    `score_weight` INT NOT NULL DEFAULT 100 COMMENT '分值权重',
    `time_limit` INT DEFAULT NULL COMMENT '单用例时间限制(ms)',
    `memory_limit` INT DEFAULT NULL COMMENT '单用例内存限制(MB)',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY `idx_function_test_question_sort` (`question_id`, `sort_order`),
    UNIQUE KEY `uk_function_test_question_hash` (`question_id`, `case_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='函数题测试用例表';
