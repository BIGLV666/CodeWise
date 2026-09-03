package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 笔记详情 VO（含全文，不含 userId）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteDetailVo {
    private Long noteId;
    private String title;
    private Long folderId;
    private String contentType;
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
