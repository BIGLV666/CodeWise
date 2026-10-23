package org.example.servicecommon.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/** 回滚路由必须绑定到 question.queue，否则事件会投递到交换机后被丢弃。 */
class MqConfigQuestionRollbackBindingTest {

    @Test
    void rollbackBindingBindsQuestionQueue() {
        MqConfig config = new MqConfig();
        Queue queue = config.questionQueue();
        Binding binding = config.rollbackQuestionBinding();

        assertThat(queue.getName()).isEqualTo(MqContexts.Question_QUEUE_NAME);
        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(binding.getDestination()).isEqualTo(MqContexts.Question_QUEUE_NAME);
        assertThat(binding.getExchange()).isEqualTo(MqContexts.Question_EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo(MqContexts.QUESTION_ROLLBACK_ROUTING_KEY);
    }

    @Test
    void cleanupBindingBindsQuestionQueue() {
        Binding binding = new MqConfig().cleanupGroupQuestionsBinding();

        assertThat(binding.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(binding.getDestination()).isEqualTo(MqContexts.Question_QUEUE_NAME);
        assertThat(binding.getExchange()).isEqualTo(MqContexts.Question_EXCHANGE);
        assertThat(binding.getRoutingKey())
                .isEqualTo(MqContexts.QUESTION_GROUP_CLEANUP_ROUTING_KEY);
    }
}
