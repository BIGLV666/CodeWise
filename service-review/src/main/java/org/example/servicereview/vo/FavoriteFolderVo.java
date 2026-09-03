package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 收藏夹瘦身 VO（agent 专用接口返回）。
 *
 * <p>相比实体 {@link org.example.servicereview.entry.Favorites}：
 * 不返回 userId 与 questionIds 明细，只返回题目数量，
 * 避免收藏夹列表随收藏规模膨胀。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteFolderVo {
    /** 收藏夹 ID。 */
    private Long favoritesId;
    /** 收藏夹名称。 */
    private String favoritesName;
    /** 收藏夹类型标签（自由字符串）。 */
    private String favoritesType;
    /** 收藏夹简介。 */
    private String favoritesContent;
    /** 收藏夹内题目数量。 */
    private Integer questionCount;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}
