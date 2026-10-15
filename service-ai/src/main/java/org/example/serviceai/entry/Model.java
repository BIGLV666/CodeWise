package org.example.serviceai.entry;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 一个 AI 端点的配置，由 Nacos 下发的 {@code ai.models} 列表逐条映射而来。
 *
 * <p>这是纯配置数据（不是 Bean）：AiServiceFactory 在装配阶段按 {@link #type} 选一个
 * {@link org.example.serviceai.ai.provider.AiProviderCreator}，由它生成具体的 Provider。
 * 新增一种接入方式（例如走订阅鉴权的网关）＝ 新增一个 Creator，Factory 不用改。</p>
 */
@Data
public class Model {
    /**
     * 接入类型，决定用哪个 {@code AiProviderCreator}。
     *
     * <p>缺省 {@code openai}——线上现存的 Nacos 配置没有这个字段，必须靠缺省值继续工作。</p>
     */
    private String type = "openai";
    /**
     * 端点身份（如 GLM / ollama）：写日志、健康记账、key 池注册、按身份借用都用它。
     *
     * <p>缺省回退 {@link #defaultModelName}。同名会被装配期告警——身份同时是健康状态与 key 池
     * 的键，重名会让两个端点共享健康状态，并让后注册的 key 列表覆盖前者的
     * （把 A 端点的地址配上 B 端点的密钥）。</p>
     */
    private String name;
    /** 该端点下可用的模型列表（对外声明"我能提供这些模型"，用于模型路由）。 */
    private List<String> modelName;
    /** 服务商请求地址（OpenAI 兼容的 chat/completions 完整地址）。 */
    private String url;
    /** 该端点的 API Key 池，调用时随机取一个（见 AiKeyPool）。 */
    private List<String> apiKeys;
    /** 是否启用该端点，缺省 true；为 false 时既不参与轮转，也不进模型选择列表。 */
    private boolean enabled = true;
    /**
     * 是否参与 AUTO 轮转与健康探测，缺省 true。
     *
     * <p>与 {@link #enabled} 的差别：置 false 表示"只在用户明确指定模型时才用"——
     * 端点仍出现在模型选择列表里、仍能被按模型路由到，但 AUTO 不会借到它。
     * OpenCode Go 这类每次请求都要求会话头的端点必须这么配：AUTO 的调用方
     * （测试用例生成、函数产物、WA 建议）手里没有会话概念，打过去会 400。</p>
     */
    private boolean autoRotate = true;
    /** 默认模型，调用方未显式指定 model 时使用。 */
    private String defaultModelName;
    /** 优先级，用于 AUTO 轮转，数值越小越优先。 */
    private Integer priority;
    /** 单次请求的连接/读取超时（毫秒）。 */
    private int timeout = 120000;
    /** 附加请求头，原样下发；供网关要求的额外头（租户、版本等）使用。 */
    private Map<String, String> headers;
}
