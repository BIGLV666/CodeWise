package org.example.servicereview.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MarkdownNoteContentHandler 单元测试：类型标识、正文校验、预览生成与标题推导。
 */
class MarkdownNoteContentHandlerTest {

    private final MarkdownNoteContentHandler handler = new MarkdownNoteContentHandler();

    @Test
    void type_isMd() {
        assertEquals("MD", handler.type());
    }

    @Test
    void validate_blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> handler.validate(null));
        assertThrows(IllegalArgumentException.class, () -> handler.validate("   "));
    }

    @Test
    void validate_normal_ok() {
        assertDoesNotThrow(() -> handler.validate("# 标题\n\n正文"));
    }

    @Test
    void validate_oversize_throws() {
        String tooLong = "a".repeat(MarkdownNoteContentHandler.MAX_CONTENT_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> handler.validate(tooLong));
    }

    @Test
    void toPreview_stripsMarkdown() {
        String md = "# 标题\n\n**加粗** 和 `code`";
        assertEquals("标题 加粗 和 code", handler.toPreview(md, 100));
    }

    @Test
    void toPreview_truncatesWithEllipsis() {
        String preview = handler.toPreview("a".repeat(50), 10);
        assertEquals(11, preview.length());
        assertTrue(preview.endsWith("…"));
    }

    @Test
    void suggestTitle_prefersHeading() {
        assertEquals("Hello", handler.suggestTitle("# Hello\n\nbody"));
    }

    @Test
    void suggestTitle_fallsBackToFirstLine() {
        assertEquals("first line", handler.suggestTitle("first line\nsecond line"));
    }

    @Test
    void suggestTitle_blankReturnsNull() {
        assertNull(handler.suggestTitle(null));
        assertNull(handler.suggestTitle("   "));
    }
}
