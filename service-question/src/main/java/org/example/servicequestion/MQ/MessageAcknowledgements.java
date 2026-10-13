package org.example.servicequestion.MQ;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;

/** Confirmation failures must not trigger a second settlement for the same delivery tag. */
@Slf4j
public final class MessageAcknowledgements {
    private MessageAcknowledgements() {
    }

    public static boolean ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
            return true;
        } catch (Exception exception) {
            log.error("消息 ACK 失败，关闭 channel 等待重新投递，deliveryTag={}", deliveryTag, exception);
            abort(channel, deliveryTag);
            return false;
        }
    }

    public static void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (Exception exception) {
            log.error("消息 NACK 失败，关闭 channel 等待重新投递，deliveryTag={}", deliveryTag, exception);
            abort(channel, deliveryTag);
        }
    }

    private static void abort(Channel channel, long deliveryTag) {
        try {
            channel.abort();
        } catch (Exception abortException) {
            log.error("确认失败后关闭 channel 失败，deliveryTag={}", deliveryTag, abortException);
        }
    }
}
