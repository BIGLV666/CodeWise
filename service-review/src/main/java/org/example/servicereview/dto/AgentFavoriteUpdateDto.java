package org.example.servicereview.dto;

import lombok.Data;

/**
 * agent 更新收藏夹元信息请求体。
 *
 * <p>与网页端 {@link ReceiveDto} 的关键差异：不接收 questionIds，
 * agent 无法通过本接口整体覆盖收藏夹的题目列表（题目增删走批量接口）。</p>
 */
@Data
public class AgentFavoriteUpdateDto {
    /** 收藏夹 ID，必填。 */
    private Long favoritesId;
    /** 新名称，可选，去空格后 1-255 字符。 */
    private String favoritesName;
    /** 新类型标签，可选，≤255 字符。 */
    private String favoritesType;
    /** 新简介，可选，≤255 字符。 */
    private String favoritesContent;
}
