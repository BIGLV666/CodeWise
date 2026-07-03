package org.example.serviceemail.service;

import com.rabbitmq.client.Channel;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.EmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.mail.SimpleMailMessage;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    Logger log = LoggerFactory.getLogger(this.getClass());
    @Autowired
    private JavaMailSender mailSender;
    @RabbitListener(queues = MqContexts.EMAIL_QUEUE)
    public void sendEmail(EmailMessage message, Channel channel, Message amqpMessage) {
        try {
            log.info("收到邮件任务，收件人: {}", message.getTo());

            // 发邮件
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom("379299583@qq.com");  // 必须和授权码邮箱一致
            mail.setTo(message.getTo());
            mail.setSubject(message.getSubject());
            mail.setText(message.getContent());
            mailSender.send(mail);

            // 成功 → 确认消息
            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
            log.info("✅ 邮件发送成功: {}", message.getTo());

        } catch (Exception e) {
            log.error("❌ 邮件发送失败: {}, 错误: {}", message.getTo(), e.getMessage());

            // 失败 → 也确认消息（避免无限重试）
            try {
                channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
            } catch (Exception ackEx) {
                log.error("确认消息失败: {}", ackEx.getMessage());
            }
        }
    }

}
