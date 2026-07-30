START TRANSACTION;

INSERT INTO `question` (
    `title`,
    `description`,
    `input_desc`,
    `output_desc`,
    `sample_input`,
    `sample_output`,
    `hint`,
    `source`,
    `content_hash`,
    `difficulty`,
    `tags`,
    `question_type`,
    `time_limit`,
    `memory_limit`,
    `total_submit`,
    `total_ac`,
    `pass_rate`,
    `status`,
    `create_user_id`,
    `ai_statue`
) VALUES (
    '无重复字符的最长子串',
    '给定一个字符串 s，请你找出其中不含有重复字符的最长子串的长度。',
    '函数参数为字符串 s。',
    '返回不含重复字符的最长子串长度。',
    'abcabcbb',
    '3',
    '答案必须是连续子串的长度，而不是子序列。',
    'LeetCode 3',
    MD5('codewise-function-leetcode-3'),
    2,
    '哈希表,字符串,滑动窗口',
    'FUNCTION',
    2000,
    256,
    0,
    0,
    0.00,
    1,
    2070815253393932289,
    'success'
) ON DUPLICATE KEY UPDATE
    `question_id` = LAST_INSERT_ID(`question_id`),
    `title` = VALUES(`title`),
    `description` = VALUES(`description`),
    `sample_input` = VALUES(`sample_input`),
    `sample_output` = VALUES(`sample_output`),
    `question_type` = 'FUNCTION',
    `update_time` = CURRENT_TIMESTAMP;

SET @function_question_id = LAST_INSERT_ID();

DELETE FROM `function_config`
WHERE `question_id` = @function_question_id;

DELETE FROM `function_test_case`
WHERE `question_id` = @function_question_id;

INSERT INTO `function_config` (
    `question_id`,
    `class_name`,
    `method_name`,
    `parameter_config`,
    `output_type`
) VALUES (
    @function_question_id,
    'Solution',
    'lengthOfLongestSubstring',
    JSON_ARRAY(JSON_OBJECT('type', 'String', 'name', 's')),
    'int'
);

INSERT INTO `function_test_case` (
    `question_id`,
    `input_data`,
    `expected_output`,
    `is_sample`,
    `is_hidden`,
    `sort_order`,
    `score_weight`,
    `time_limit`,
    `memory_limit`
) VALUES
    (@function_question_id, 'abcabcbb', '3', 1, 0, 1, 20, 2000, 256),
    (@function_question_id, 'bbbbb',    '1', 1, 0, 2, 20, 2000, 256),
    (@function_question_id, 'pwwkew',   '3', 1, 0, 3, 20, 2000, 256),
    (@function_question_id, '',         '0', 1, 0, 4, 20, 2000, 256),
    (@function_question_id, 'dvdf',     '3', 1, 0, 5, 20, 2000, 256);

COMMIT;

SELECT
    @function_question_id AS question_id,
    q.title,
    q.question_type,
    fc.class_name,
    fc.method_name,
    fc.parameter_config,
    fc.output_type
FROM `question` q
JOIN `function_config` fc ON fc.question_id = q.question_id
WHERE q.question_id = @function_question_id;

SELECT
    function_test_case_id,
    input_data,
    expected_output,
    is_sample,
    sort_order
FROM `function_test_case`
WHERE question_id = @function_question_id
ORDER BY sort_order;
