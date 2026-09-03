package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量添加题目到收藏夹的结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteAddResultVo {
    /** 成功新增的题目数量。 */
    private Integer added;
    /** 因已在收藏夹中被跳过的题目 ID。 */
    private List<Long> skippedDuplicateIds;
    /** 因不可见（他人私密/下架/审核中）被跳过的题目 ID。 */
    private List<Long> skippedInvisibleIds;
    /** 不存在的题目 ID。 */
    private List<Long> invalidQuestionIds;
}
