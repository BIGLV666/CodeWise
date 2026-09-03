-- 存量库手工升级脚本：新增进度计划追踪表（用户自定义题单）。
-- 新部署无需执行：docker 初始化脚本已通过 /sql/review/sql.sql 全量建表。
-- 表结构说明见 sql/sql.sql 中 progress_tracker 的注释。
CREATE TABLE IF NOT EXISTS progress_tracker (
    progress_id bigint not null primary key auto_increment comment '进度追踪ID',
    user_id bigint not null comment '用户ID',
    question_id bigint not null comment '题目ID',
    submit_ids JSON default null comment '当天提交记录ID集合（存在提交记录时才有值，存在AC即视为完成）',
    status tinyint not null default 0 comment '状态: 0-未开始 1-进行中 2-已完成 3-已过期',
    notes_content text default null comment '学习计划备注（用户自定义题单描述）',
    summary_content text default null comment '完成后的反思总结',
    begin_time date not null comment '计划开始日期（已开始/过期后不允许修改，过期可整体重新规划）',
    create_time datetime not null default current_timestamp comment '创建时间',
    update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
    unique key uk_tracker_user_question (user_id, question_id),
    index idx_tracker_user_begin (user_id, begin_time),
    index idx_tracker_status (status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='进度计划追踪表(自定义题单)';
