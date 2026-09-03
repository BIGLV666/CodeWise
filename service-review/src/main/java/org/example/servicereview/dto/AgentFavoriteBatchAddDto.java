package org.example.servicereview.dto;

import lombok.Data;

import java.util.List;

/**
 * agent 批量添加题目到收藏夹请求体。
 */
@Data
public class AgentFavoriteBatchAddDto {
    /** 收藏夹 ID，必填。 */
    private Long favoriteId;
    /** 题目 ID 列表，必填，去重后 1-50 个。 */
    private List<Long> questionIds;
}
