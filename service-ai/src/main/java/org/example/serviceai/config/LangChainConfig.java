package org.example.serviceai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
@Configuration
public class LangChainConfig {

    @Bean(name = "myChatModel")
    public ChatModel chatModel() {
        return OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen2.5:14b")
                .temperature(0.7)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(0)
                .build();
    }


}
