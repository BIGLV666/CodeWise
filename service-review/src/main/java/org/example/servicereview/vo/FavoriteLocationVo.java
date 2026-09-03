package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个题目在当前用户各收藏夹中的分布（agent 定位接口返回）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteLocationVo {
    /** 题目 ID。 */
    private Long questionId;
    /** 包含该题目的收藏夹列表；无则为空列表。 */
    private List<FavoriteFolderBriefVo> folders;
}
