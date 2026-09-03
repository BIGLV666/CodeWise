package org.example.servicereview.handler;

/**
 * 笔记内容类型处理器（多类型扩展点）。
 *
 * <p>笔记正文当前仅支持 Markdown，但正文的「校验 / 预览 / 标题推导」等类型相关行为
 * 通过本接口抽象。新增一种内容类型（如图片、代码、链接）时，只需新增一个实现本接口的
 * Spring Bean（声明自己的 {@link #type()}），即可被
 * {@link NoteContentHandlerRegistry} 自动收集，无需改动任何已有类。</p>
 */
public interface NoteContentHandler {

    /** 内容类型标识，写入 note.content_type（约定为大写，如 "MD"）。 */
    String type();

    /** 校验正文合法性（必填、长度等）；非法时抛出 {@link IllegalArgumentException}。 */
    void validate(String content);

    /** 生成列表卡片用的纯文本预览：去除标记符号并截断到指定长度。 */
    String toPreview(String content, int maxLength);

    /** 当用户未提供标题时，从正文推导默认标题；无法推导时返回 null（由调用方兜底）。 */
    default String suggestTitle(String content) {
        return null;
    }
}
