package org.example.servicereview.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 笔记文件夹（分类）：用户自定义的一级分类，笔记通过 {@code folder_id} 挂靠其下。
 *
 * <p>层级仅一级：文件夹不再嵌套子文件夹；笔记可不归属任何文件夹
 * （{@code note.folder_id} 为 NULL 表示「未分类」）。同一用户下文件夹名称唯一。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("note_folder")
public class NoteFolder {

    @TableId(type = IdType.AUTO)
    private Long folderId;

    /** 归属用户 ID（取自网关注入的 UserContext，不接收客户端提交）。 */
    private Long userId;

    /** 文件夹名称（同一用户下唯一）。 */
    private String folderName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
