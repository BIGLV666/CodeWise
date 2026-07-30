package org.example.serviceai;

import org.example.serviceapi.dto.ai.AiAdviceWADto;
import org.example.servicecommon.config.MqContexts;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "security.api-key-master-key=wcfKdHJkghwFn7PKkc5BH96mw39FJnZ121Camt+jppY=")
class ServiceAiApplicationTests {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void contextLoads() {
    }

}
