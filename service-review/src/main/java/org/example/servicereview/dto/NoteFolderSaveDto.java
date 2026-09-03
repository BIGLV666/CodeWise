package org.example.servicereview.dto;

import lombok.Data;

/**
 * 创建/重命名笔记文件夹请求体。
 *
 * <p>requestId 仅创建时用于幂等（复用 Redis requestId 键），重命名时忽略。</p>
 */
@Data
public class NoteFolderSaveDto {
    /** 文件夹名称，必填，去空格后 1-255 字符。 */
    private String folderName;
    /** 幂等键，可选；提供时同一 requestId 只允许建一次。 */
    private String requestId;
}
