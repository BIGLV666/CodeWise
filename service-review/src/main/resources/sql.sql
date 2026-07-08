create table favorites (
    favorites_id bigint not null primary key auto_increment comment '收藏id',
    favorites_name varchar(255) not null comment '收藏名称',
    favorites_type varchar(255)  comment '收藏类型',
    favorites_content varchar(255)  comment '收藏描述',
    user_id bigint not null comment '用户id',
    question_ids JSON not null comment '问题id',
    create_time datetime not null default current_timestamp comment '创建时间',
    update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
    Index idx_favorites_type (favorites_type)
);

-- ==========================================================
-- 复习表（基于 SM-2 间隔重复算法）
-- 每条记录代表：某用户 对 某题目 的复习状态
-- ==========================================================
create table review (
    review_id bigint not null primary key auto_increment comment '复习记录ID',

    -- ========== 关联字段 ==========
    user_id bigint not null comment '用户ID',
    question_id bigint not null comment '题目ID',

    -- ========== SM-2 核心参数 ==========
    easiness_factor decimal(4,2) not null default 2.50 comment '难度因子EF，初始2.5，最小不低于1.3',
    repetitions int not null default 0 comment '连续正确复习次数n，quality<3时重置为0',
    interval_days int not null default 0 comment '当前复习间隔天数I(n)',
    last_quality tinyint default null comment '上次复习质量评分q(0-5)',

    -- ========== 时间调度字段 ==========
    last_review_time datetime default null comment '上次复习时间',
    next_review_time datetime default null comment '下次应复习时间，用于查询待复习列表',

    -- ========== 统计字段 ==========
    review_count int not null default 0 comment '累计复习次数(含正确与错误)',

    -- ========== 状态 ==========
    weight INTEGER not null default 0 comment '权重，默认为0',
    status tinyint not null default 0 comment '状态: 0-学习中 1-已掌握 2-暂停',

    -- ========== 审计字段 ==========
    create_time datetime not null default current_timestamp comment '创建时间',
    update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',

    -- ========== 索引 ==========
    unique uk_user_question (user_id, question_id),
    index idx_next_review_time (next_review_time),
    index idx_user_id (user_id),
    index idx_status (status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='复习记录表(SM-2算法)';

-- ==========================================================
-- 用户复习配置表
-- 每条记录代表：某用户的复习计划配置与 SM-2 参数边界
-- ==========================================================
create table review_config (
    review_config_id bigint not null primary key auto_increment comment '复习配置ID',

    -- ========== 关联字段 ==========
    user_id bigint not null comment '用户ID，每个用户一份复习配置',

    -- ========== 复习计划配置 ==========
    review_count int not null default 2147483647 comment '每天最多推荐/安排复习的题目数量，2147483647表示不限制',
    enable_auto_review tinyint not null default 1 comment '是否启用自动复习计划: 0-关闭 1-启用',
    count_compile_error tinyint not null default 1 comment '编译失败是否计入复习: 0-不计入 1-计入并按quality=0处理',

    -- ========== SM-2 参数配置 ==========
    min_easiness_factor decimal(4,2) not null default 1.30 comment '最低难度因子EF，常用下限1.3',
    initial_easiness_factor decimal(4,2) not null default 2.50 comment '新复习题目的初始难度因子EF，默认2.5',
    mastered_interval_days int not null default 30 comment '掌握判定间隔天数，interval_days达到该值后可标记为已掌握',

    -- ========== 审计字段 ==========
    create_time datetime not null default current_timestamp comment '创建时间',
    update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',

    -- ========== 索引 ==========
    unique uk_user_id (user_id),
    index idx_enable_auto_review (enable_auto_review)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='用户复习配置表';

-- ==========================================================
-- 每日复习记录表
-- 每条记录代表：某用户某一天的复习任务快照
-- pending_review_question_ids：当天待完成复习题目
-- completed_review_question_ids：当天已经完成复习题目
-- ac_question_ids：当天复习中 AC 的题目，用于统计/展示
-- review_days：连续复习天数
-- ==========================================================
create table review_record (
    review_record_id bigint not null primary key auto_increment comment '每日复习记录ID',

    -- ========== 关联字段 ==========
    user_id bigint not null comment '用户ID',

    -- ========== 每日复习题目快照 ==========
    pending_review_question_ids JSON not null comment '当天待复习题目ID列表',
    completed_review_question_ids JSON not null comment '当天已完成复习题目ID列表',
    ac_question_ids JSON default null comment '当天复习中AC的题目ID列表',

    -- ========== 连续复习统计 ==========
    review_days int not null default 0 comment '连续复习天数',

    -- ========== 业务日期 ==========
    review_date date not null comment '复习日期',

    -- ========== 审计字段 ==========
    create_time datetime not null default current_timestamp comment '创建时间',

    -- ========== 索引 ==========
    unique uk_user_review_date (user_id, review_date),
    index idx_user_id (user_id),
    index idx_create_time (create_time),
    index idx_review_date (review_date)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='每日复习记录表';

