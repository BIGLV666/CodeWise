package org.example.servicereview.handler;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 笔记内容类型处理器注册表。
 *
 * <p>Spring 自动注入所有 {@link NoteContentHandler} Bean，按 {@code type()} 建索引
 * （键统一大写，大小写不敏感）。新增类型只需新增实现类，无需修改本类。
 * 未指定类型时回退默认类型 {@link MarkdownNoteContentHandler}。</p>
 */
@Component
public class NoteContentHandlerRegistry {

    /** 默认内容类型，未显式指定类型时使用。 */
    public static final String DEFAULT_TYPE = MarkdownNoteContentHandler.TYPE;

    private final Map<String, NoteContentHandler> handlers;

    public NoteContentHandlerRegistry(List<NoteContentHandler> handlerList) {
        Map<String, NoteContentHandler> map = new LinkedHashMap<>();
        for (NoteContentHandler handler : handlerList) {
            if (handler != null && handler.type() != null && !handler.type().isBlank()) {
                map.put(handler.type().toUpperCase(Locale.ROOT), handler);
            }
        }
        this.handlers = map;
    }

    /**
     * 解析类型：type 为空时回退默认类型；未知类型抛出业务异常。
     *
     * @param type 客户端提交的类型标识，可为空
     * @return 对应的处理器
     */
    public NoteContentHandler resolve(String type) {
        String key = (type == null || type.isBlank())
                ? DEFAULT_TYPE.toUpperCase(Locale.ROOT)
                : type.trim().toUpperCase(Locale.ROOT);
        NoteContentHandler handler = handlers.get(key);
        if (handler == null) {
            throw new IllegalArgumentException("不支持的内容类型：" + type);
        }
        return handler;
    }

    /** 默认处理器（Markdown）。 */
    public NoteContentHandler defaultHandler() {
        return resolve(DEFAULT_TYPE);
    }
}
