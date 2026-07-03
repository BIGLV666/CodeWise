create table user(
    user_id BIGINT AUTO_INCREMENT primary key ,
    user_name VARCHAR(30) NOT NULL ,
    password VARCHAR(64) NOT NULL ,
    email VARCHAR(30),
    phone VARCHAR(20) ,
    bio JSON ,
    nick_name VARCHAR(30) NOT NULL ,
    avatar_url VARCHAR(100),
    birthday DATE ,
    role_id INTEGER DEFAULT 1 ,
    status INTEGER DEFAULT 1,

    open_id VARCHAR(100) ,
    total_submit BIGINT DEFAULT 0,
    total_ac BIGINT DEFAULT 0,
    rating BIGINT DEFAULT 0,

    create_time DATETIME DEFAULT (CURRENT_TIMESTAMP) ,
    update_time DATETIME DEFAULT (CURRENT_TIMESTAMP),

    last_login_ip VARCHAR(100) not null ,
    last_login_time DATETIME not null ,
    ban_time DATETIME,
    UNIQUE INDEX idx_username (user_name),
    UNIQUE INDEX idx_email (email),
    UNIQUE INDEX idx_phone(phone)


);