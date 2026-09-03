package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 笔记列表游标分页结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteListVo {
    private List<NoteBriefVo> items;
    /** 下一页游标（本页最后一条 note_id；无更多时为 null）。 */
    private Long nextCursor;
    /** 是否还有下一页。 */
    private Boolean hasNext;
    /** 满足过滤条件的笔记总数。 */
    private Long total;
}
