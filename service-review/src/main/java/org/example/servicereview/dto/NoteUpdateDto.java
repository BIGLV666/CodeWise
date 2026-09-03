package org.example.servicereview.dto;

import lombok.Data;

/**
 * 更新笔记请求体（仅标题/正文可改；内容类型创建后不可变）。
 */
@Data
public class NoteUpdateDto {
    /** 标题，可选。 */
    private String title;
    /** 正文，可选。 */
    private String content;
}
