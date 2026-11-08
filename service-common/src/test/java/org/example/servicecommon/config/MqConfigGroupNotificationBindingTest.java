package org.example.servicecommon.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;

import static org.assertj.core.api.Assertions.assertThat;

/** 小组广播/审批结果路由必须绑定到 notification.queue，否则消息会被交换机静默丢弃。 */
class MqConfigGroupNotificationBindingTest {

    @Test
    void broadcastBindingBindsNotificationQueue() {
        Binding binding = new MqConfig().notificationBroadcastBinding();

        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(binding.getDestination()).isEqualTo(MqContexts.NOTIFICATION_QUEUE_NAME);
        assertThat(binding.getExchange()).isEqualTo(MqContexts.NOTIFICATION_EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(MqContexts.NOTIFICATION_BROADCAST_ROUTING_KEY);
    }

    @Test
    void approveResultBindingBindsNotificationQueue() {
        Binding binding = new MqConfig().notificationApproveResultBinding();

        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(binding.getDestination()).isEqualTo(MqContexts.NOTIFICATION_QUEUE_NAME);
        assertThat(binding.getExchange()).isEqualTo(MqContexts.NOTIFICATION_EXCHANGE);
        assertThat(binding.getRoutingKey())
                .isEqualTo(MqContexts.NOTIFICATION_APPROVE_RESULT_ROUTING_KEY);
    }
}
