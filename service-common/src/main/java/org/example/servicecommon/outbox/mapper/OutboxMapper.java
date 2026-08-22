package org.example.servicecommon.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.servicecommon.outbox.OutboxEvent;

import java.util.List;

/**
 * Outbox 事件表 Mapper。
 *
 * <p>所有 SQL 均使用 #{} 参数绑定，禁止拼接。</p>
 */
@Mapper
public interface OutboxMapper extends BaseMapper<OutboxEvent> {

    /**
     * 认领一批到期待投递事件（行锁 + 跳过已锁行）。
     *
     * <p>必须在事务内调用：FOR UPDATE SKIP LOCKED 保证多实例 Relay
     * 并发认领同一张表时互不阻塞、互不重复投递（MySQL 8.0+）。</p>
     *
     * @param limit 批量上限
     * @return 认领到的事件列表
     */
    @Select("SELECT * FROM event_outbox WHERE status = 'PENDING' "
            + "AND (next_retry_time IS NULL OR next_retry_time <= NOW()) "
            + "ORDER BY outbox_id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<OutboxEvent> claimPendingBatch(@Param("limit") int limit);

    /**
     * 人工重放：将 DEAD 事件重置为待投递（参数绑定，仅允许 DEAD -> PENDING）。
     *
     * @param outboxId 事件主键
     * @return 影响行数
     */
    @Update("UPDATE event_outbox SET status = 'PENDING', retry_count = 0, "
            + "next_retry_time = NULL, last_error = NULL WHERE outbox_id = #{outboxId} AND status = 'DEAD'")
    int replayDead(@Param("outboxId") Long outboxId);
}
