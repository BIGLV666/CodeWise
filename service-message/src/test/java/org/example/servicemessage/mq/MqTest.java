package org.example.servicemessage.mq;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQ 分发器兜底行为测试：处理器抛出未捕获异常（如 DB 抖动导致幂等 claim 失败）时
 * 退避后 nack 重投（消息不丢）；找不到处理器时 nack 丢弃。
 */
@ExtendWith(MockitoExtension.class)
class MqTest {

    @Mock
    private MessageHandler handler;
    @Mock
    private Channel channel;

    @InjectMocks
    private Mq mq;

    @BeforeEach
    void setUp() {
        mq.handlerMap.put("email.routing", handler);
        mq.infraBackoffMs = 0;
    }

    private Message buildMessage(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setReceivedRoutingKey("email.routing");
        return new Message("{}".getBytes(), properties);
    }

    @Test
    void handlerExceptionTriggersBackoffAndRequeue() throws Exception {
        doThrow(new RuntimeException("db down")).when(handler).handle(any(), any(), any());

        mq.consume(buildMessage(7L), "email.routing", channel);

        verify(channel).basicNack(7L, false, true);
        verify(channel, never()).basicAck(eq(7L), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void unknownRoutingKeyIsNackedWithoutRequeue() throws Exception {
        mq.consume(buildMessage(9L), "unknown.routing", channel);

        verify(channel).basicNack(9L, false, false);
    }

    @Test
    void handlerSuccessDoesNotNack() throws Exception {
        mq.consume(buildMessage(11L), "email.routing", channel);

        verify(channel, never()).basicNack(eq(11L), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void handlerIoFailureAlsoRequeues() throws Exception {
        // 处理器内部 ACK/NACK 的 IO 失败同样走兜底重投；若 channel 真的断裂，
        // 兜底 nack 自身抛出的 IOException 会自然交还容器触发 broker 重投
        doThrow(new java.io.IOException("channel closed")).when(handler).handle(any(), any(), any());

        mq.consume(buildMessage(13L), "email.routing", channel);

        verify(channel).basicNack(13L, false, true);
    }

    @Test
    void nackFailurePropagatesToContainer() throws Exception {
        doThrow(new RuntimeException("db down")).when(handler).handle(any(), any(), any());
        doThrow(new java.io.IOException("channel broken"))
                .when(channel).basicNack(13L, false, true);

        assertThrows(java.io.IOException.class,
                () -> mq.consume(buildMessage(13L), "email.routing", channel));
    }

    @Test
    void constructorRegistersHandlersByRoutingKey() {
        when(handler.getRoutingKey()).thenReturn("email.routing");
        Mq fresh = new Mq(List.of(handler));
        fresh.init();

        org.junit.jupiter.api.Assertions.assertTrue(fresh.handlerMap.containsKey("email.routing"));
    }
}
