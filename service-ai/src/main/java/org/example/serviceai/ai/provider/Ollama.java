package org.example.serviceai.ai.provider;

import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "myChatModel"
)
public interface Ollama {
    String callAi(String prompt) ;
}
