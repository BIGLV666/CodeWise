package org.example.serviceai.service;

import org.example.serviceai.intifer.CallAi;
import org.example.serviceai.intifer.Ollama;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
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
     * 检查服务是否可用（健康检查）
     */
    @Override
    public boolean isAvailable() {
        return true;
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
