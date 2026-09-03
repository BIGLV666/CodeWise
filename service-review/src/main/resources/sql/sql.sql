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
    index idx_status (status),
    index idx_review_schedule (user_id, status, next_review_time)
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

-- ==========================================================
-- 已消费事件幂等与状态表（复习判题消息消费幂等）
-- event_id 为全局幂等键：claim 插入 PROCESSING，业务成功转 COMPLETED，超限转 FAILED
-- ==========================================================
CREATE TABLE IF NOT EXISTS consumed_event (
    id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    event_id VARCHAR(64) NOT NULL COMMENT '全局唯一事件ID（幂等键）',
    routing_key VARCHAR(64) DEFAULT NULL COMMENT '来源路由键',
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING' COMMENT '状态：PROCESSING/COMPLETED/FAILED',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '已失败尝试次数',
    last_error VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败原因（截断）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_event_id (event_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='已消费事件幂等与状态表';

-- ==========================================================
-- 进度计划追踪表（用户自定义题单）
-- 每条记录代表：某用户 对 某题目 的学习进度计划
-- submit_ids：当天该题目的提交记录ID集合，仅记录当天新增；
--             存在 AC 提交时由判题消费方置为 COMPLETED(2)
-- status：整数编码（实体为 Integer）：0-未开始 1-进行中 2-已完成 3-已过期；
--         0→1 / →3 的流转为懒加载判定（查询时顺手刷新，无常驻任务）
-- begin_time：DATE 类型，与实体 LocalDate 对应，按天精确查询/聚合
-- ==========================================================
create table progress_tracker (
    progress_id bigint not null primary key auto_increment comment '进度追踪ID',

    -- ========== 关联字段 ==========
    user_id bigint not null comment '用户ID',
    question_id bigint not null comment '题目ID',

    -- ========== 进度内容 ==========
    submit_ids JSON default null comment '当天提交记录ID集合（存在提交记录时才有值，存在AC即视为完成）',
    status tinyint not null default 0 comment '状态: 0-未开始 1-进行中 2-已完成 3-已过期',
    notes_content text default null comment '学习计划备注（用户自定义题单描述）',
    summary_content text default null comment '完成后的反思总结',
    begin_time date not null comment '计划开始日期（已开始/过期后不允许修改，过期可整体重新规划）',

    -- ========== 审计字段 ==========
    create_time datetime not null default current_timestamp comment '创建时间',
    update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',

    -- ========== 索引 ==========
    -- 每个用户对每道题只有一条计划（重复创建抛 DuplicateKeyException，
    -- 过期后通过更新 begin_time 重新规划，不另起新行）
    unique key uk_tracker_user_question (user_id, question_id),
    -- 日期列表聚合与按天精确查询的主路径：用户 + 日期
    index idx_tracker_user_begin (user_id, begin_time),
    index idx_tracker_status (status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='进度计划追踪表(自定义题单)';


