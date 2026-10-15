package org.example.serviceai.ai.provider;

import org.example.serviceai.ai.AiRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * LangChain4j 驱动的本地 Ollama 端点。
 *
 * <p>它是 Bean，但<b>不在</b> {@code ai.models} 的自动轮转列表里（Factory 只按配置建端点），
 * 因此不参与 AUTO 借用；需要它的路径（会话摘要）按名借用。Bean 始终存在，
 * 是否参与轮转由 {@link #enabled()} 表达——用 {@code @ConditionalOnProperty} 删 Bean
 * 会让依赖它的上下文启动失败。</p>
 */
@Service
public class OllamaProvider implements AiProvider {

    private static final String NAME = "ollama";

    private final Ollama ollama;
    private final boolean enabled;

    public OllamaProvider(Ollama ollama, @Value("${ollama.enabled:true}") boolean enabled) {
        this.ollama = ollama;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 由 {@code ollama.enabled=false} 关闭时不参与轮转与探测，Bean 仍保留。 */
    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public int priority() {
        return 999;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(120);
    }

    @Override
    public String chat(AiRequest request) {
        return ollama.callAi(request.prompt());
    }

    @Override
    public void chatStream(AiRequest request, Consumer<String> onChunk) {
        onChunk.accept(chat(request));
    }
}
