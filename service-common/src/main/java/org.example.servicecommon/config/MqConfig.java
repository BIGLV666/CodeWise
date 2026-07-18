package org.example.servicecommon.config;


import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import static org.example.servicecommon.config.MqContexts.*;

@AutoConfiguration
@ConditionalOnMissingBean(Queue.class)
public class MqConfig {
    @PostConstruct
    public void init() {
        System.out.println("✅ RabbitMQConfig 被加载了！");
    }

    // ========== 邮件队列 ==========
    @Bean
    public Queue emailQueue() {
        System.out.println("✅ 创建 email.queue 队列");
        return new Queue(MESSAGE_QUEUE_NAME, true);
    }

    @Bean
    public DirectExchange emailExchange() {
        System.out.println("✅ 创建 email.exchange 交换机");
        return new DirectExchange(MESSAGE_EXCHANGE, true, false);
    }

    @Bean
    public Binding emailBinding() {
        System.out.println("✅ 绑定 email.queue 到 email.exchange");
        return BindingBuilder
                .bind(emailQueue())
                .to(emailExchange())
                .with(MESSAGE_ROUTING_KEY);
    }
    @Bean
    public Binding websocketBinding() {
        return BindingBuilder
                .bind(emailQueue())
                .to(emailExchange())
                .with(WEBSOCKET_ROUTING_KEY);
    }
    // ========== 判题队列 ==========
    @Bean
    public Queue judgeQueue() {
        return QueueBuilder.durable(JUDGE_QUEUE_NAME).build();
    }

    @Bean
    public DirectExchange judgeExchange() {
        return new DirectExchange(MqContexts.JUDGE_EXCHANGE, true, false);
    }

    @Bean
    public Binding judgeBinding() {
        return BindingBuilder
                .bind(judgeQueue())
                .to(judgeExchange())
                .with(MqContexts.JUDGE_ROUTING_KEY);
    }
    @Bean
    public Binding judgeBinding2() {
        return BindingBuilder
                .bind(judgeQueue())
                .to(judgeExchange())
                .with(MqContexts.JUDGE_DEBUG_ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
    //==============Ai===========队列
    @Bean
    public Queue aiQueue(){
        return QueueBuilder.durable(Ai_QUEUE_NAME).build();
    }
    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(Ai_EXCHANGE, true, false);
    }

    @Bean
    public Binding aiTestBinding() {
        return BindingBuilder
                .bind(aiQueue())
                .to(aiExchange())
                .with(Ai_TESTCASE_ROUTING_KEY);
    }
    @Bean
    public Binding REAdviceBinding() {
        return BindingBuilder
                .bind(aiQueue())
                .to(aiExchange())
                .with(AI_WA_ADVICE_ROUTING_KEY);
    }
    //======================================题目队列==========================
    @Bean
    public Queue questionQueue() {
        return QueueBuilder.durable(Question_QUEUE_NAME).build();
    }
    @Bean
    public DirectExchange questionExchange() {
        return new DirectExchange(Question_EXCHANGE, true, false);
    }
    @Bean
    public Binding questionBinding() {
        return BindingBuilder
                .bind(questionQueue())
                .to(questionExchange())
                .with(Question_TESTCASE_ROUTING_KEY);
    }
    @Bean
    public Binding deleteQueueBinding() {
        return BindingBuilder
                .bind(questionQueue())
                .to(questionExchange())
                .with(QUESTION_DELETE_QUESTION_ROUTING_KEY);
    }
    @Bean
    public Binding submitRecordBinding() {
        return BindingBuilder
                .bind(questionQueue())
                .to(questionExchange())
                .with(QUESTION_SUBMIT_RECORD_ROUTING_KEY);
    }
    @Bean
    public Binding debugBinding() {
        return BindingBuilder
                .bind(questionQueue())
                .to(questionExchange())
                .with(QUESTION_DEBUG_ROUTING_KEY);
    }
    //=============复习队列================
    @Bean
    public Queue reviewQueue() {
        return QueueBuilder.durable(REVIEW_QUEUE_NAME).build();
    }
    @Bean
    public DirectExchange reviewExchange() {
        return new DirectExchange(REVIEW_EXCHANGE, true, false);
    }
    @Bean
    public Binding reviewBinding() {
        return BindingBuilder
                .bind(reviewQueue())
                .to(reviewExchange())
                .with(REVIEW_JUDGE_RECORD_ROUTING_KEY);
    }
    //===============用户队列================
    @Bean
    public Queue userQueue() {
        return QueueBuilder.durable(USER_QUEUE_NAME).build();
    }
    @Bean
    public DirectExchange userExchange() {
        return new DirectExchange(USER_EXCHANGE, true, false);
    }
    @Bean
    public Binding userBinding() {
        return BindingBuilder
                .bind(userQueue())
                .to(userExchange())
                .with(USER_JUDGE_ROUTING_KEY);

    }

    //========================收件箱队列====================

    @Bean
    public Queue NotificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE_NAME).build();
    }
    @Bean
    public DirectExchange NotificationExchange() {
        return new DirectExchange(NOTIFICATION_EXCHANGE, true, false);
    }
    @Bean
    public Binding notificationLikeBinding() {
        return BindingBuilder
                .bind(NotificationQueue())
                .to(NotificationExchange())
                .with(NOTIFICATION_LIKE_ROUTING_KEY);
    }
    @Bean
    public Binding notificationReviewBinding() {
        return BindingBuilder
                .bind(NotificationQueue())
                .to(NotificationExchange())
                .with(NOTIFICATION_REVIEW_ROUTING_KEY);
    }
    @Bean
    public Binding notificationAiAdviceBinding() {
        return BindingBuilder
                .bind(NotificationQueue())
                .to(NotificationExchange())
                .with(NOTIFICATION_AI_ADVICE_ROUTING_KEY);
    }

}
