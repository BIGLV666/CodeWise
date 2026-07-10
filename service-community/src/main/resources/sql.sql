create TABLE post(
    post_id BIGINT NOT NULL primary key AUTO_INCREMENT,
    post_title VARCHAR(200) NOT NULL comment '主帖标题',
    post_content LONGTEXT not null ,
    user_id BIGINT not null ,
    user_name VARCHAR(30) not null ,
    like_count BIGINT not null default 0,
    comment_count BIGINT not null default 0,
    status INTEGER not null default 0,
    create_time DATETIME not null DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME not null DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id(user_id),
    INDEX idx_create_time(create_time),
    INDEX idx_status_create_time(status, create_time)

);
CREATE TABLE comment(
    comment_id BIGINT not null PRIMARY KEY AUTO_INCREMENT,
    comment LONGTEXT not null ,
    user_id BIGINT not null ,
    user_name VARCHAR(30) not null ,
    post_id BIGINT not null ,
    root_comment_id BIGINT ,
    reply_user_id BIGINT ,
    reply_user_name VARCHAR(30) ,
    like_count BIGINT not null default 0,
    status INTEGER not null default 0,
    create_time DATETIME not null DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME not null DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_post_root(post_id, root_comment_id),
    INDEX idx_post_create(post_id, create_time),
    INDEX idx_user_id(user_id)


);
CREATE TABLE like_record(
    like_record_id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT not null ,
    post_id BIGINT not null ,
    type VARCHAR(10) not null ,
    create_time DATETIME not null DEFAULT CURRENT_TIMESTAMP,
    UNIQUE uk_idx_user_id(user_id,post_id,type)
);
create TABLE tags(
    tag_id BIGINT not null PRIMARY KEY AUTO_INCREMENT,
    tag_name VARCHAR(20) not null ,
    post_id BIGINT not null ,
    index idx_tag(tag_name),
    UNIQUE KEY uk_post_tag(post_id, tag_name)

);
