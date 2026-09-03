package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 跨收藏夹移动题目的结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteMoveResultVo {
    /** 成功移动的题目数量。 */
    private Integer moved;
    /** 不在源收藏夹中的题目 ID（未做任何处理）。 */
    private List<Long> notInSourceIds;
    /** 已存在于目标收藏夹中的题目 ID（保留在源收藏夹中未动，避免数据丢失）。 */
    private List<Long> alreadyInTargetIds;
}
