package org.example.servicequestion.MQ;

import com.rabbitmq.client.Channel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MqTest {
    @Mock Channel channel;
    private Mq mq;
    private MessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = mock(MessageHandler.class);
        when(handler.getRoutingKey()).thenReturn("known.route");
        mq = new Mq(List.of(handler));
        mq.init();
    }

    @Test
    void unknownRouteIsNackedOnceWithoutRequeue() throws Exception {
        mq.mq(message(1L), "unknown.route", channel, message(1L));
        verify(channel).basicNack(1L, false, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void handlerExceptionUsesOuterRequeueNackOnce() throws Exception {
        doThrow(new IllegalStateException("uncaught")).when(handler).handle(anyString(), eq(channel), any(Message.class));
        mq.mq(message(2L), "known.route", channel, message(2L));
        verify(channel).basicNack(2L, false, true);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void handledFailureDoesNotGetSecondOuterNack() throws Exception {
        doAnswer(invocation -> {
            MessageAcknowledgements.nack(channel, 3L, false);
            return null;
        }).when(handler).handle(anyString(), eq(channel), any(Message.class));
        mq.mq(message(3L), "known.route", channel, message(3L));
        verify(channel).basicNack(3L, false, false);
        verifyNoMoreInteractions(channel);
    }

    private static Message message(long tag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(tag);
        return new Message("payload".getBytes(), properties);
    }
}
