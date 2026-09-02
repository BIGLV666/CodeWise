package org.example.serviceuser.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 一次性初始化标记表 Mapper。
 * <p>
 * sys_init 表记录已经执行过的初始化项。借助 init_key 唯一索引 + INSERT IGNORE，
 * 在并发/多实例/重启场景下都能保证某个初始化项只被成功执行一次。
 */
@Mapper
public interface SysInitMapper {

    /**
     * 建表（幂等）。数据库由外部管理，初始化时自我补齐该表，避免依赖外部 DDL。
     */
    @Update("""
            CREATE TABLE IF NOT EXISTS sys_init (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                init_key VARCHAR(64) NOT NULL,
                created_at DATETIME DEFAULT (CURRENT_TIMESTAMP),
                UNIQUE KEY uk_init_key (init_key)
            )
            """)
    void ensureTable();

    /**
     * 尝试登记初始化项，仅当 init_key 尚不存在时返回 1，否则返回 0。
     * 返回值即"是否应执行初始化"的唯一判定依据。
     */
    @Insert("INSERT IGNORE INTO sys_init(init_key, created_at) VALUES(#{initKey}, NOW())")
    int tryRegister(String initKey);

    /**
     * 仅用于日志/调试：查询某初始化项是否已登记。
     */
    @Select("SELECT COUNT(*) FROM sys_init WHERE init_key = #{initKey}")
    int countKey(String initKey);

    /**
     * 初始化失败时回滚登记标记，使下次启动可以重试。
     * 仅在「本次登记成功但后续初始化失败」时调用，不会误删他人已完成的登记。
     */
    @Delete("DELETE FROM sys_init WHERE init_key = #{initKey}")
    int unregister(String initKey);
}