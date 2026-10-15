package org.example.serviceai.ai.provider;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenCode Go 协议：仍是 OpenAI 兼容体，但每次请求必须带会话头，且请求体多两个字段。
 *
 * <p>关键点：会话头 <b>不是鉴权头</b>——鉴权仍是 {@code Authorization: Bearer}，
 * 这个头承载的是「一个会话一个稳定 id」，上游拿它当 prompt cache 的分组键。</p>
 *
 * <p>缺会话头时<b>省略而不是伪造</b>：发一个占位值会静默污染上游缓存，比缺头（明确 400）
 * 更难查。首次遇到缺会话标识会打一条 WARN，因为那必然是调用方没把会话 id 传下来。</p>
 */
@Slf4j
public final class OpenCodeProtocol implements AiProtocol {

    public static final OpenCodeProtocol INSTANCE = new OpenCodeProtocol();

    /** 上游必需的会话头；缺失直接 {@code 400 MissingSessionID}，且在鉴权之前判定。 */
    public static final String SESSION_HEADER = "x-opencode-session";
    /** 客户端还会带这个头；上游要求两者同时存在时必须相等，所以这里写成同一个值。 */
    public static final String SESSION_ID_HEADER = "x-opencode-session-id";

    private final AtomicBoolean warnedMissingSession = new AtomicBoolean();

    private OpenCodeProtocol() {
    }

    @Override
    public String name() {
        return "opencode-go";
    }

    @Override
    public String buildBody(String model, String prompt, int maxTokens, String sessionRef) {
        return OpenCodeChatRequest.body(
                model, prompt, maxTokens, OpenCodeSessionId.toSessionId(sessionRef));
    }

    @Override
    public Map<String, String> perRequestHeaders(String sessionRef) {
        String sessionId = OpenCodeSessionId.toSessionId(sessionRef);
        if (sessionId == null) {
            if (warnedMissingSession.compareAndSet(false, true)) {
                log.warn("OpenCode Go 端点没有会话标识（AiRequest.sessionRef 为空）：上游要求 {} 头、"
                                + "缺失会返回 400。这里只省略、不伪造占位值。"
                                + "请确认调用方传入了稳定的会话 id。",
                        SESSION_HEADER);
            }
            return Map.of();
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(SESSION_HEADER, sessionId);
        headers.put(SESSION_ID_HEADER, sessionId);
        return headers;
    }
}
