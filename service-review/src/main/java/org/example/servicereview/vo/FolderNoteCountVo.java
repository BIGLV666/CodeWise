package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件夹笔记计数 VO（{@code NoteMapper#countByFolder} 的分组结果）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderNoteCountVo {
    /** 文件夹 ID；未分类分组为 null。 */
    private Long folderId;
    /** 笔记数量。 */
    private Long noteCount;
}
