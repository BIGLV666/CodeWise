package org.example.serviceai.ai.transport;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 兼容 SSE 分片的解析：唯一实现，供平台端点与用户自定义配置共用。
 *
 * <p>这里刻意容忍 {@code data:} 后不带空格：SSE 规范允许省略，部分网关（如 new-api）
 * 就会省掉，只认 {@code "data: "} 会静默丢块、把长回答截断。</p>
 */
public final class SseParser {

    private static final Logger log = LoggerFactory.getLogger(SseParser.class);
    private static final String DATA_PREFIX = "data:";
    private static final String DONE = "[DONE]";

    private SseParser() {
    }

    /** 从一行 SSE 文本里取出增量内容；非数据行、心跳、{@code [DONE]}、无 delta 一律返回 null。 */
    public static String parseChunk(String provider, String line) {
        if (line == null || !line.startsWith(DATA_PREFIX)) {
            return null;
        }
        String data = line.substring(DATA_PREFIX.length()).trim();
        if (data.isEmpty() || DONE.equals(data)) {
            return null;
        }
        try {
            JSONObject chunk = new JSONObject(data);
            JSONArray choices = chunk.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
            return delta == null ? null : delta.getStr("content");
        } catch (Exception exception) {
            log.warn("{} stream chunk parse failed: {}", provider, exception.getMessage());
            return null;
        }
    }
}
