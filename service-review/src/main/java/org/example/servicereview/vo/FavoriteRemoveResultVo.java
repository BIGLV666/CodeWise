package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 从收藏夹批量移除题目的结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteRemoveResultVo {
    /** 成功移除的题目数量。 */
    private Integer removed;
    /** 本就不在该收藏夹中的题目 ID。 */
    private List<Long> notInFolderIds;
}
