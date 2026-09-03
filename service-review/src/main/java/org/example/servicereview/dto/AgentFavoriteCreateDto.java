package org.example.servicereview.dto;

import lombok.Data;

/**
 * agent 创建收藏夹请求体。
 *
 * <p>requestId 由 agent 客户端生成（UUID），复用网页端的 Redis 幂等键，
 * 网络重试不会重复建夹。</p>
 */
@Data
public class AgentFavoriteCreateDto {
    /** 收藏夹名称，必填，去空格后 1-255 字符。 */
    private String favoritesName;
    /** 收藏夹类型标签，可选，≤255 字符。 */
    private String favoritesType;
    /** 收藏夹简介，可选，≤255 字符。 */
    private String favoritesContent;
    /** 幂等键，必填。 */
    private String requestId;
}
