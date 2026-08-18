package org.example.servicemessage.email.emailService;

import com.rabbitmq.client.Channel;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {
    @Mock
    private JavaMailSender mailSender;

    @Mock
    private Channel channel;

    @InjectMocks
    private EmailService emailService;

    @Test
    void handleParsesJsonMessageAndAcknowledgesIt() throws Exception {
        String body = "{\"to\":\"user@example.com\",\"subject\":\"CodeWise\",\"content\":\"验证码:192648\"}";
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        Message message = new Message(body.getBytes(StandardCharsets.UTF_8), properties);
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.handle(body, channel, message);

        verify(mailSender).send(mimeMessage);
        verify(channel).basicAck(42L, false);
    }
}
