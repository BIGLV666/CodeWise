package org.example.servicereview.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 笔记模块建表初始化器。
 *
 * <p>笔记模块要求「完全新增、不改动共享 SQL 文件」，故在应用启动完成后幂等建表
 * （CREATE TABLE IF NOT EXISTS）。业务账号已被部署初始化脚本授予 codewise_review 库的
 * CREATE 权限，与 OutboxPro 启动自建 outboxpro_* 表同思路。</p>
 *
 * <p>表结构亦沉淀于 resources/migration/V4__note.sql，仅供存量老库手工升级/对账，
 * 与运行时建表保持一致。</p>
 */
@Slf4j
@Component
public class NoteSchemaInitializer implements ApplicationRunner {

    private static final String CREATE_NOTE_FOLDER = """
            CREATE TABLE IF NOT EXISTS note_folder (
                folder_id bigint not null primary key auto_increment comment '笔记文件夹ID',
                user_id bigint not null comment '用户ID',
                folder_name varchar(255) not null comment '文件夹名称',
                create_time datetime not null default current_timestamp comment '创建时间',
                update_time datetime not null default current_timestamp on update current_timestamp comment '更新时间',
                unique key uk_note_folder_user_name (user_id, folder_name)
            ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='笔记文件夹(一级分类)';
            """;

    private static final String CREATE_NOTE = """
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
            """;

    private final DataSource dataSource;

    public NoteSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(CREATE_NOTE_FOLDER);
        jdbc.execute(CREATE_NOTE);
        log.info("note 模块表结构初始化完成");
    }
}
