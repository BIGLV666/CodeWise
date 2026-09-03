package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 笔记文件夹 VO（含笔记数量，不含 userId）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteFolderVo {
    private Long folderId;
    private String folderName;
    /** 文件夹内笔记数量。 */
    private Integer noteCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
