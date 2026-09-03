package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收藏夹极简信息（定位场景使用）：仅 ID 与名称。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteFolderBriefVo {
    /** 收藏夹 ID。 */
    private Long favoriteId;
    /** 收藏夹名称。 */
    private String favoritesName;
}
