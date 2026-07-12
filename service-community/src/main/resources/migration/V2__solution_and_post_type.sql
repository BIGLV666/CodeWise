-- Existing comment/tag rows belong to community posts, so the new discriminator defaults to POST.
ALTER TABLE comment
    ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'POST'
        COMMENT '目标类型：POST社区帖子，SOLUTION题解' AFTER like_count;

ALTER TABLE comment
    DROP INDEX idx_post_root,
    DROP INDEX idx_post_create,
    ADD INDEX idx_post_root(post_id, type, root_comment_id),
    ADD INDEX idx_post_create(post_id, type, create_time);

ALTER TABLE tags
    ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'POST'
        COMMENT '目标类型：POST社区帖子，SOLUTION题解' AFTER post_id;

ALTER TABLE tags
    DROP INDEX uk_post_tag,
    ADD UNIQUE KEY uk_post_tag(post_id, tag_name, type);

CREATE TABLE solution(
    solution_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    solution_title VARCHAR(200) NOT NULL COMMENT '题解标题',
    solution_content LONGTEXT NOT NULL COMMENT '题解正文',
    solution_user_id BIGINT NOT NULL COMMENT '题解作者',
    like_count BIGINT NOT NULL DEFAULT 0,
    look_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0,
    status INTEGER NOT NULL DEFAULT 1 COMMENT '0待审核，1正常，2下架',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_question_status_create(question_id, status, create_time),
    INDEX idx_solution_user(solution_user_id)
);
