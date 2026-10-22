package org.example.servicejudge.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RabbitTemplateConfig {

    @Bean
    public RabbitTemplate rabbitTemplate(RabbitTemplateConfigurer configurer,
                                         ConnectionFactory connectionFactory,
                                         ObjectProvider<RabbitTemplateCustomizer> customizers) {
        RabbitTemplate template = new RabbitTemplate();
        configurer.configure(template, connectionFactory);
        customizers.orderedStream().forEach(customizer -> customizer.customize(template));
        // MqConfig can back off for a custom Queue or disabled topology. JSON must still work.
        // Do not register another MessageConverter bean or mutate Boot's shared configurer.
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }
}
