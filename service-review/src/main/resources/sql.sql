create table favorites (
    favorites_id bigint not null primary key auto_increment comment '收藏id',
    favorites_name varchar(255) not null comment '收藏名称',
    favorites_type varchar(255)  comment '收藏类型',
    favorites_content varchar(255)  comment '收藏描述',
    user_id bigint not null comment '用户id',
    question_ids JSON not null comment '问题id',
    create_time datetime not null default current_timestamp comment '创建时间',
    update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
    Index idx_favorites_type (favorites_type),
    Index idx_question_id (question_id)
);