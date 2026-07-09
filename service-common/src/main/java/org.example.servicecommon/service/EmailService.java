package org.example.servicecommon.service;

import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.EmailMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送邮件任务到 MQ
     */
    public void sendEmail(String to, String subject, String content) {
        EmailMessage message = new EmailMessage(to, subject, content);
        rabbitTemplate.convertAndSend(
                MqContexts.MESSAGE_EXCHANGE,
                MqContexts.MESSAGE_ROUTING_KEY,
                message
        );
        System.out.println("邮件任务已发送: " + to);
    }


}
