-- 笔记模块建表脚本（仅供存量老库手工升级/对账）。
-- 新部署无需手工执行：NoteSchemaInitializer 在应用启动完成后幂等建表（CREATE TABLE IF NOT EXISTS）。
-- 表结构与 config/NoteSchemaInitializer 中的 DDL 保持一致。

CREATE TABLE IF NOT EXISTS note_folder (
    folder_id bigint not null primary key auto_increment comment '笔记文件夹ID',
    user_id bigint not null comment '用户ID',
    folder_name varchar(255) not null comment '文件夹名称',
    create_time datetime not null default current_timestamp comment '创建时间',
    update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
    unique key uk_note_folder_user_name (user_id, folder_name)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='笔记文件夹(一级分类)';

CREATE TABLE IF NOT EXISTS note (
    note_id bigint not null primary key auto_increment comment '笔记ID',
    user_id bigint not null comment '用户ID',
    folder_id bigint null comment '所属文件夹ID（NULL 表示未分类）',
    title varchar(255) not null comment '笔记标题',
    content mediumtext not null comment '笔记正文（Markdown）',
    content_type varchar(32) not null default 'MD' comment '内容类型标识（如 MD），创建后不可变',
    create_time datetime not null default current_timestamp comment '创建时间',
    update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
    key idx_note_user_folder (user_id, folder_id),
    key idx_note_user_type (user_id, content_type)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='笔记表';
