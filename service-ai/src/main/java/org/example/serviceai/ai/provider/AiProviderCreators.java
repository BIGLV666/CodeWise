package org.example.serviceai.ai.provider;

import org.example.serviceai.entry.Model;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Creator 之间共用的取值兜底逻辑。 */
final class AiProviderCreators {

    private AiProviderCreators() {
    }

    /**
     * 端点身份：优先服务名，缺省回退默认模型名。
     *
     * <p>日志、健康记账、key 池注册三者共用这一个值。历史上日志用服务名、而健康与 key 池
     * 用默认模型名，导致两条配置只要默认模型名相同就会共享健康状态、并且后注册的会覆盖
     * 前者的 key 列表（把 A 端点的 URL 配上 B 端点的密钥）。</p>
     */
    static String identityOf(Model model) {
        return model.getName() != null && !model.getName().isBlank()
                ? model.getName().trim()
                : model.getDefaultModelName();
    }

    static int priorityOf(Model model) {
        return model.getPriority() == null ? 0 : model.getPriority();
    }

    /** 该端点声明的模型列表，缺省时退化为默认模型。 */
    static List<String> modelNamesOf(Model model) {
        List<String> names = model.getModelName();
        if (names == null || names.isEmpty()) {
            return model.getDefaultModelName() == null
                    ? List.of()
                    : List.of(model.getDefaultModelName());
        }
        return names;
    }

    /**
     * 该端点实际服务的模型集合：{@code modelName} ∪ {@code defaultModelName}。
     *
     * <p>默认模型一定要并进来——AUTO 用的就是它，如果它不在声明列表里，用户在模型选择列表里
     * 就看不到、也选不到这个正在被使用的模型。</p>
     */
    static Set<String> servedModelsOf(Model model) {
        Set<String> models = new LinkedHashSet<>();
        for (String name : modelNamesOf(model)) {
            if (name != null && !name.isBlank()) {
                models.add(name.trim());
            }
        }
        String defaultModel = model.getDefaultModelName();
        if (defaultModel != null && !defaultModel.isBlank()) {
            models.add(defaultModel.trim());
        }
        return Collections.unmodifiableSet(models);
    }

    /**
     * 把一条配置翻译成端点快照；协议是唯一按 type 变化的部分。
     *
     * <p>鉴权当前一律 Bearer：OpenCode Go 也是普通 {@code Authorization: Bearer}，
     * 它的会话头是「路由/缓存键」而不是鉴权头，所以走协议而不是 {@link AuthScheme}。</p>
     */
    static AiEndpointSpec specOf(Model model, AiProtocol protocol) {
        return new AiEndpointSpec(
                identityOf(model),
                model.getUrl(),
                model.getDefaultModelName(),
                servedModelsOf(model),
                protocol,
                AuthScheme.bearer(),
                model.getHeaders(),
                model.isEnabled(),
                priorityOf(model),
                Duration.ofMillis(model.getTimeout())
        );
    }
}
