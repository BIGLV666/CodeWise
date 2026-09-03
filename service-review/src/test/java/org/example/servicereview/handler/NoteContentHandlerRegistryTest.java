package org.example.servicereview.handler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * NoteContentHandlerRegistry 单元测试：默认类型回退、大小写不敏感、未知类型拦截。
 */
class NoteContentHandlerRegistryTest {

    private final NoteContentHandlerRegistry registry =
            new NoteContentHandlerRegistry(List.of(new MarkdownNoteContentHandler()));

    @Test
    void resolve_nullFallsBackToDefault() {
        assertEquals(MarkdownNoteContentHandler.TYPE, registry.resolve(null).type());
        assertEquals(MarkdownNoteContentHandler.TYPE, registry.resolve("  ").type());
    }

    @Test
    void resolve_isCaseInsensitive() {
        assertEquals(MarkdownNoteContentHandler.TYPE, registry.resolve("md").type());
        assertEquals(MarkdownNoteContentHandler.TYPE, registry.resolve("MD").type());
    }

    @Test
    void resolve_unknownType_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("IMAGE"));
    }

    @Test
    void defaultHandler_isMarkdown() {
        assertEquals(MarkdownNoteContentHandler.TYPE, registry.defaultHandler().type());
    }
}
