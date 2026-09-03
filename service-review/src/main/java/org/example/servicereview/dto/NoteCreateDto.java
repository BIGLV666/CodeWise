package org.example.servicereview.dto;

import lombok.Data;

/**
 * 创建笔记请求体。
 */
@Data
public class NoteCreateDto {
    /** 所属文件夹 ID；可空/0 表示未分类，正数表示挂靠到对应文件夹。 */
    private Long folderId;
    /** 标题，可空；为空时从正文推导。 */
    private String title;
    /** 正文，必填。 */
    private String content;
    /** 内容类型标识，可空，默认 "MD"。 */
    private String type;
    /** 幂等键，可选；提供时同一 requestId 只允许建一次。 */
    private String requestId;
}
