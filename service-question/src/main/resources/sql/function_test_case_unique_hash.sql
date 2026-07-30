ALTER TABLE `function_test_case`
    ADD COLUMN `case_hash` CHAR(64) GENERATED ALWAYS AS (
        SHA2(CONCAT(`input_data`, CHAR(0), `expected_output`), 256)
    ) STORED COMMENT '输入输出 SHA-256 哈希' AFTER `expected_output`,
    ADD UNIQUE KEY `uk_function_test_question_hash` (`question_id`, `case_hash`);
