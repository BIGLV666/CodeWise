package org.example.servicereview.dto;

import lombok.Data;

import java.util.List;

/**
 * agent 跨收藏夹移动题目请求体。
 *
 * <p>服务端在事务内以行锁（按主键升序 FOR UPDATE）锁住源与目标两条记录后再读写，
 * 防止与网页端/其他会话的并发写互相覆盖。</p>
 */
@Data
public class AgentFavoriteMoveDto {
    /** 源收藏夹 ID，必填。 */
    private Long fromFavoriteId;
    /** 目标收藏夹 ID，必填，不能与源相同。 */
    private Long toFavoriteId;
    /** 要移动的题目 ID 列表，必填，去重后 1-50 个。 */
    private List<Long> questionIds;
}
