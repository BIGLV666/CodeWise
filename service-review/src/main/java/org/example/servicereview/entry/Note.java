package org.example.servicereview.entry;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 笔记：用户的一篇 Markdown 笔记。
 *
 * <p>正文类型经 {@code NoteContentHandler} 抽象（当前仅 MD），便于后续扩展图片/代码等
 * 多类型文件；{@code contentType} 创建后不可变。{@code folderId} 可空：为空表示「未分类」，
 * 非空则挂靠在某个文件夹下（一级嵌套）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("note")
public class Note {

    @TableId(type = IdType.AUTO)
    private Long noteId;

    /** 归属用户 ID（取自网关注入的 UserContext，不接收客户端提交）。 */
    private Long userId;

    /** 所属文件夹 ID；NULL 表示未分类。 */
    // ALWAYS：更新时即使为 null 也写入，保证「移到未分类」能真正清空 folder_id
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long folderId;

    /** 笔记标题。 */
    private String title;

    /** 正文（Markdown 文本，服务端不做 HTML 清洗，XSS 由前端渲染层负责）。 */
    private String content;

    /** 内容类型标识（如 "MD"），由 NoteContentHandler 识别，创建后不可变。 */
    private String contentType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
