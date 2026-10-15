package org.example.serviceai.ai;

import java.util.Objects;

/**
 * 一次 AI 调用的请求值对象。
 *
 * <p>把散装的位置参数收成一个对象是拆分 AI 层最直接的收益：改造前有
 * {@code callAi(String)} / {@code callAi(String,int)} / {@code callAi(String,String)} /
 * {@code streamAi(String,Consumer)} / {@code streamAi(String,Consumer,String)}，
 * 用户自定义路径更是 {@code streamAi(prompt, consumer, userId, configId, modelName)}
 * ——五个位置参数里三个是 {@code Long}/{@code String}，写错顺序编译器不报错。</p>
 */
public record AiRequest(
        String prompt,
        int maxTokens,
        AiTarget target,
        String modelName,
        String sessionRef
) {

    public static final int DEFAULT_MAX_TOKENS = 10240;

    public AiRequest {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(target, "target");
        if (maxTokens <= 0) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
    }

    /**
     * 附加会话标识。
     *
     * <p>同一会话的每次请求必须传同一个值——协议拿它做分组/缓存键（例如 OpenCode Go 的
     * {@code x-opencode-session} 与 {@code prompt_cache_key}）。它<b>必须跨轮稳定</b>，
     * 所以只能取会话主键这类不随请求变化的值，不能掺 prompt 或时间戳。</p>
     *
     * <p>没有会话概念的路径（测试用例生成、函数产物等批量生成）可以不传；此时由协议决定
     * 降级方式（OpenCode Go 会省略该头并告警，见 {@code OpenCodeProtocol}）。</p>
     */
    public AiRequest withSessionRef(String sessionRef) {
        return new AiRequest(prompt, maxTokens, target, modelName, sessionRef);
    }

    /** AUTO：什么都不选，平台按服务商轮转，模型用被借到端点的默认模型。 */
    public static AiRequest auto(String prompt) {
        return new AiRequest(prompt, DEFAULT_MAX_TOKENS, new AiTarget.PlatformAuto(), null, null);
    }

    public static AiRequest auto(String prompt, int maxTokens) {
        return new AiRequest(prompt, maxTokens, new AiTarget.PlatformAuto(), null, null);
    }

    /** 指定平台端点（按名借用，不做故障切换）。 */
    public static AiRequest provider(String providerName, String prompt) {
        return provider(providerName, prompt, DEFAULT_MAX_TOKENS);
    }

    public static AiRequest provider(String providerName, String prompt, int maxTokens) {
        return new AiRequest(prompt, maxTokens, new AiTarget.PlatformProvider(providerName), null, null);
    }

    /** 指定平台模型：用户明确选了平台栏里的某个模型，按模型路由、不轮转、不兜底。 */
    public static AiRequest model(String prompt, String modelName) {
        return model(prompt, modelName, DEFAULT_MAX_TOKENS);
    }

    public static AiRequest model(String prompt, String modelName, int maxTokens) {
        return new AiRequest(prompt, maxTokens, new AiTarget.PlatformModel(), modelName, null);
    }

    /** 用户自带的模型配置：userId 显式传入并由归属校验兜底（不依赖请求头推断）。 */
    public static AiRequest userConfig(String prompt, Long userId, Long configId, String modelName) {
        return new AiRequest(
                prompt, DEFAULT_MAX_TOKENS, new AiTarget.UserConfig(userId, configId), modelName, null);
    }
}
