package org.example.serviceai.service;

import org.example.serviceai.intifer.CallAi;
import org.example.serviceai.intifer.Ollama;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
// 可由 Nacos 下发 ollama.enabled=false 静音该 Provider：未部署 Ollama 时，周期性探测
// 会持续输出 "Failed to connect to localhost:11434" 的噪音日志。matchIfMissing=true 默认开启。
@ConditionalOnProperty(prefix = "ollama", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OllamaService implements CallAi {
    @Autowired
    private Ollama ollama;
    @Override
    public String getModelName() {
        return "ollama";
    }

    @Override
    public String callAi(String prompt) {
        return  ollama.callAi(prompt);

    }
    /**
     * 获取服务优先级（数值越小优先级越高）
     */
    @Override
   public int getPriority() {
        return 999;
    }

    /**
     * 获取超时时间（毫秒）
     */
    @Override
    public  int getTimeout() {
        return 120000;
    }
    @Override
    public void streamAi(String prompt, Consumer<String> onChunk)
    {

        onChunk.accept(callAi(prompt));
    }
}
