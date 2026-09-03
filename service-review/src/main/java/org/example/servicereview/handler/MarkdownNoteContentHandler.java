package org.example.servicereview.handler;

import org.springframework.stereotype.Component;

/**
 * Markdown 笔记内容处理器：当前唯一的内容类型实现。
 */
@Component
public class MarkdownNoteContentHandler implements NoteContentHandler {

    /** 内容类型标识。 */
    public static final String TYPE = "MD";

    /** 正文长度上限（1MB），超限拒绝入库，防止单条笔记撑爆存储。 */
    public static final int MAX_CONTENT_LENGTH = 1024 * 1024;

    /** 标题长度上限，与 note.title varchar(255) 对齐。 */
    public static final int MAX_TITLE_LENGTH = 255;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void validate(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("笔记内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("笔记内容过长，最多 " + MAX_CONTENT_LENGTH + " 字符");
        }
    }

    @Override
    public String toPreview(String content, int maxLength) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        // 去掉常见 Markdown 标记与代码围栏，压缩空白后截断，得到可读的纯文本预览
        String plain = content
                .replaceAll("```[a-zA-Z]*[\\r\\n]?[\\s\\S]*?```", " ")
                .replaceAll("`([^`]*)`", "$1")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("_{1,2}([^_]+)_{1,2}", "$1")
                .replaceAll("\\s+", " ")
                .trim();
        if (plain.length() <= maxLength) {
            return plain;
        }
        return plain.substring(0, maxLength) + "…";
    }

    @Override
    public String suggestTitle(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String[] lines = content.split("\\r?\\n");
        // 优先取首个 Markdown 标题行（# 开头），否则取首个非空行
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^#{1,6}\\s+.+")) {
                return truncateTitle(trimmed.replaceAll("^#{1,6}\\s+", ""));
            }
        }
        for (String line : lines) {
            if (!line.isBlank()) {
                return truncateTitle(line.trim());
            }
        }
        return null;
    }

    private String truncateTitle(String title) {
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }
}
