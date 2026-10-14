package org.example.serviceai.entry;

import lombok.Data;

import java.util.List;

/**
 * 一个 AI 端点的配置，由 Nacos 下发的 {@code ai.models} 列表逐条映射而来。
 *
 * <p>这是纯配置数据（不是 Bean）：AIServiceManager 在装配阶段按每个 Model 生成一个
 * {@link org.example.serviceai.service.AiAggregateService}。</p>
 */
@Data
public class Model {
    /** 服务名，仅用于日志识别与人工排查（如 GLM / GPT / ollama）。 */
    private String name;
    /** 该端点下可用的模型列表。 */
    private List<String> modelName;
    /** 服务商请求地址（OpenAI 兼容的 chat/completions 完整地址）。 */
    private String url;
    /** 该端点的 API Key 池，调用时随机取一个（见 GetAPiKey）。 */
    private List<String> apiKeys;
    /** 是否参与轮转与健康探测，缺省 true；为 false 时被 AIServiceManager 过滤掉。 */
    private boolean enabled = true;
    /** 默认模型，调用方未显式指定 model 时使用。 */
    private String defaultModelName;
    /** 优先级，用于 AUTO 轮转，数值越小越优先。 */
    private Integer priority;
    /** 单次请求的连接/读取超时（毫秒）。 */
    private int timeout = 120000;
}
