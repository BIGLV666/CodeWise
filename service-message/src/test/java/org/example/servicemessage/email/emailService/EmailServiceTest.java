package org.example.servicemessage.email.emailService;

import com.rabbitmq.client.Channel;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.example.servicemessage.consumedevent.service.ConsumedEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {
    private static final long DELIVERY_TAG = 42L;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private ConsumedEventService consumedEventService;

    @Mock
    private Channel channel;

    @InjectMocks
    private EmailService emailService;

    @Test
    void handleParsesJsonMessageAndAcknowledgesIt() throws Exception {
        String body = "{\"eventId\":\"event-1\",\"to\":\"user@example.com\",\"subject\":\"CodeWise\",\"content\":\"验证码:192648\"}";
        when(consumedEventService.claim(eq("event-1"), anyString()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.handle(body, channel, amqpMessage(body));

        verify(mailSender).send(mimeMessage);
        verify(consumedEventService).complete("event-1");
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void legacyMessageWithoutEventIdGetsGeneratedOne() throws Exception {
        String body = "{\"to\":\"user@example.com\",\"subject\":\"CodeWise\",\"content\":\"hello\"}";
        when(consumedEventService.claim(anyString(), anyString()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        emailService.handle(body, channel, amqpMessage(body));

        ArgumentCaptor<String> eventIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(consumedEventService).complete(eventIdCaptor.capture());
        assertFalse(eventIdCaptor.getValue().isBlank());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void duplicateCompletedEventIsAcknowledgedWithoutSending() throws Exception {
        String body = "{\"eventId\":\"event-1\",\"to\":\"user@example.com\",\"subject\":\"CodeWise\",\"content\":\"hello\"}";
        when(consumedEventService.claim(eq("event-1"), anyString()))
                .thenReturn(ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED);

        emailService.handle(body, channel, amqpMessage(body));

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void sendFailureBelowRetryLimitRequeues() throws Exception {
        String body = "{\"eventId\":\"event-1\",\"to\":\"user@example.com\",\"subject\":\"CodeWise\",\"content\":\"hello\"}";
        when(consumedEventService.claim(eq("event-1"), anyString()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        when(consumedEventService.recordFailure(eq("event-1"), anyString())).thenReturn(1);

        emailService.handle(body, channel, amqpMessage(body));

        verify(consumedEventService, never()).complete(anyString());
        verify(channel).basicNack(DELIVERY_TAG, false, true);
    }

    @Test
    void sendFailureAtRetryLimitMarksFailedAndDiscards() throws Exception {
        String body = "{\"eventId\":\"event-1\",\"to\":\"user@example.com\",\"subject\":\"CodeWise\",\"content\":\"hello\"}";
        when(consumedEventService.claim(eq("event-1"), anyString()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        when(consumedEventService.recordFailure(eq("event-1"), anyString())).thenReturn(3);

        emailService.handle(body, channel, amqpMessage(body));

        verify(consumedEventService).markFailed(eq("event-1"), anyString());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    void invalidPayloadIsDeadLetteredWithoutClaim() throws Exception {
        String body = "not-json";

        emailService.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
    }

    private Message amqpMessage(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
