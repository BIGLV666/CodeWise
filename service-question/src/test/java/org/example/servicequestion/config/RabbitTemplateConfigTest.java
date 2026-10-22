package org.example.servicequestion.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.example.servicecommon.config.MqConfig;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitTemplateConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, MqConfig.class))
            .withUserConfiguration(RabbitTemplateConfig.class)
            .withPropertyValues("spring.rabbitmq.dynamic=false", "spring.rabbitmq.template.exchange=boot.exchange",
                    "spring.rabbitmq.template.routing-key=boot.routing",
                    "spring.rabbitmq.template.receive-timeout=1234ms", "spring.rabbitmq.template.reply-timeout=4321ms");

    @Test
    void enabledTopologyKeepsOneTemplateAndJsonDtoConversion() {
        runner.withPropertyValues("codewise.mq.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RabbitTemplate.class).hasSingleBean(MqConfig.class);
            assertThat(context.getBeansOfType(Queue.class)).isNotEmpty();
            verifyTemplate(context.getBean(RabbitTemplate.class));
        });
    }

    @Test
    void disabledTopologyStillUsesJson() {
        runner.withPropertyValues("codewise.mq.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MqConfig.class).doesNotHaveBean(Queue.class);
            verifyTemplate(context.getBean(RabbitTemplate.class));
        });
    }

    @Test
    void customQueueBackoffStillUsesJson() {
        runner.withPropertyValues("codewise.mq.enabled=true").withUserConfiguration(CustomQueue.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MqConfig.class).hasSingleBean(Queue.class);
            assertThat(context.getBean(Queue.class).getName()).isEqualTo("custom.queue");
            verifyTemplate(context.getBean(RabbitTemplate.class));
        });
    }

    @Test
    void bootPropertiesAndOrderedCustomizersSurviveWhileFinalConverterIsJson() {
        runner.withUserConfiguration(Customizers.class).run(context -> {
            assertThat(context).hasNotFailed();
            RabbitTemplate template = context.getBean(RabbitTemplate.class);
            assertThat(context.getBean(Customizers.class).calls).containsExactly("first", "second");
            assertThat(template.getExchange()).isEqualTo("custom.exchange");
            assertThat(template.getRoutingKey()).isEqualTo("boot.routing");
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(template, "receiveTimeout")).isEqualTo(1234L);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(template, "replyTimeout")).isEqualTo(4321L);
            verifyJson(template);
        });
    }

    private static void verifyTemplate(RabbitTemplate template) {
            assertThat(template.getExchange()).isEqualTo("boot.exchange");
        assertThat(template.getRoutingKey()).isEqualTo("boot.routing");
        verifyJson(template);
    }

    private static void verifyJson(RabbitTemplate template) {
        assertThat(template.getMessageConverter()).isInstanceOf(Jackson2JsonMessageConverter.class);
        WebsocketSendDto dto = new WebsocketSendDto();
        dto.setUserId(7L);
        dto.setQueueName("debug-judge-result");
        dto.setResult(List.of("AC"));
        Message message = template.getMessageConverter().toMessage(dto, new MessageProperties());
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains("\"userId\":7", "\"result\":[\"AC\"]");
        assertThat(template.getMessageConverter().fromMessage(message)).isInstanceOf(WebsocketSendDto.class);
        Message uuid = template.getMessageConverter().toMessage("task-id", new MessageProperties());
        assertThat(new String(uuid.getBody(), StandardCharsets.UTF_8)).isEqualTo("\"task-id\"");
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomQueue {
        @Bean Queue customQueue() { return new Queue("custom.queue"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class Customizers {
        final List<String> calls = new ArrayList<>();
        @Bean @Order(10) RabbitTemplateCustomizer first() {
            return template -> { calls.add("first"); template.setExchange("custom.exchange"); };
        }
        @Bean @Order(20) RabbitTemplateCustomizer second() {
            return template -> { calls.add("second"); template.setMessageConverter(new SimpleMessageConverter()); };
        }
    }
}
