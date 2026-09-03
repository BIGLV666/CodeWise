package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 笔记列表瘦身 VO（含预览，不含全文与 userId）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteBriefVo {
    private Long noteId;
    private String title;
    private Long folderId;
    private String contentType;
    /** 纯文本预览（去除 Markdown 标记并截断）。 */
    private String preview;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
