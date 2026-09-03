package org.example.servicecommon.config;


import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import static org.example.servicecommon.config.MqContexts.*;

@AutoConfiguration
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(name = "codewise.mq.enabled", havingValue = "true")
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
    public DirectExchange judgeExchange() {
        return new DirectExchange(MqContexts.JUDGE_EXCHANGE, true, false);
    }

    // ========== 判题队列 v2：submit/debug/retry 独立队列，统一挂 judge.dlx ==========
    // 旧 judge.queue 因 broker 端参数不可变无法补挂 DLX，已弃用（不再声明）。

    @Bean
    Queue judgeSubmitQueue() {
        return QueueBuilder.durable(JUDGE_SUBMIT_QUEUE)
                .deadLetterExchange(JUDGE_DLX)
                .deadLetterRoutingKey(JUDGE_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue judgeDebugQueue() {
        return QueueBuilder.durable(JUDGE_DEBUG_QUEUE)
                .deadLetterExchange(JUDGE_DLX)
                .deadLetterRoutingKey(JUDGE_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue judgeRetryQueue() {
        return QueueBuilder.durable(JUDGE_RETRY_QUEUE)
                .deadLetterExchange(JUDGE_DLX)
                .deadLetterRoutingKey(JUDGE_DEAD_ROUTING_KEY)
                .build();
    }

    /**
     * 延迟重试等待队列：无消费者。消费失败时按指数退避设置 per-message TTL
     * 投入本队列，TTL 到期后经 DLX 弹回 judge.submit.queue，实现无插件的延迟重试。
     */
    @Bean
    Queue judgeWaitQueue() {
        return QueueBuilder.durable(JUDGE_WAIT_QUEUE)
                .deadLetterExchange(JUDGE_EXCHANGE)
                .deadLetterRoutingKey(JUDGE_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding judgeSubmitBinding() {
        return BindingBuilder
                .bind(judgeSubmitQueue())
                .to(judgeExchange())
                .with(MqContexts.JUDGE_ROUTING_KEY);
    }

    @Bean
    public Binding judgeDebugBinding() {
        return BindingBuilder
                .bind(judgeDebugQueue())
                .to(judgeExchange())
                .with(MqContexts.JUDGE_DEBUG_ROUTING_KEY);
    }

    @Bean
    public Binding judgeRetryBinding() {
        return BindingBuilder
                .bind(judgeRetryQueue())
                .to(judgeExchange())
                .with(MqContexts.JUDGE_RETRY_ROUTING_KEY);
    }

    @Bean
    DirectExchange judgeDeadExchange() {
        return new DirectExchange(JUDGE_DLX);
    }

    @Bean
    Queue judgeDeadQueue() {
        return QueueBuilder.durable(JUDGE_DLQ).build();
    }
    @Bean
    Binding judgeDeadBinding() {
        return BindingBuilder.bind(judgeDeadQueue())
                .to(judgeDeadExchange())
                .with(JUDGE_DEAD_ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }





    //==============Ai===========队列
    // ========== AI 队列 v2：testcase/advice 独立队列，统一挂 ai.dlx ==========
    // 旧 ai.queue 因 broker 端参数不可变无法补挂 DLX，已弃用（不再声明）。

    @Bean
    Queue aiTestcaseQueue() {
        return QueueBuilder.durable(AI_TESTCASE_QUEUE)
                .deadLetterExchange(AI_DLX)
                .deadLetterRoutingKey(AI_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue aiAdviceQueue() {
        return QueueBuilder.durable(AI_ADVICE_QUEUE)
                .deadLetterExchange(AI_DLX)
                .deadLetterRoutingKey(AI_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(Ai_EXCHANGE, true, false);
    }

    @Bean
    public Binding aiTestBinding() {
        return BindingBuilder
                .bind(aiTestcaseQueue())
                .to(aiExchange())
                .with(Ai_TESTCASE_ROUTING_KEY);
    }

    @Bean
    public Binding aiAdviceBinding() {
        return BindingBuilder
                .bind(aiAdviceQueue())
                .to(aiExchange())
                .with(AI_WA_ADVICE_ROUTING_KEY);
    }

    /**
     * 延迟重试等待队列：无消费者。消费失败时按指数退避设置 per-message TTL
     * 投入本队列，TTL 到期后经 DLX 弹回 ai.testcase.queue，实现无插件的延迟重试。
     */
    @Bean
    Queue aiTestcaseWaitQueue() {
        return QueueBuilder.durable(AI_TESTCASE_WAIT_QUEUE)
                .deadLetterExchange(Ai_EXCHANGE)
                .deadLetterRoutingKey(Ai_TESTCASE_ROUTING_KEY)
                .build();
    }

    /**
     * 延迟重试等待队列：无消费者。TTL 到期后经 DLX 弹回 ai.advice.queue。
     */
    @Bean
    Queue aiAdviceWaitQueue() {
        return QueueBuilder.durable(AI_ADVICE_WAIT_QUEUE)
                .deadLetterExchange(Ai_EXCHANGE)
                .deadLetterRoutingKey(AI_WA_ADVICE_ROUTING_KEY)
                .build();
    }

    /** AI 死信交换机与死信队列：毒消息、重试超限统一死信于此，供人工重放。 */
    @Bean
    DirectExchange aiDeadExchange() {
        return new DirectExchange(AI_DLX);
    }

    @Bean
    Queue aiDeadQueue() {
        return QueueBuilder.durable(AI_DLQ).build();
    }

    @Bean
    Binding aiDeadBinding() {
        return BindingBuilder.bind(aiDeadQueue()).to(aiDeadExchange()).with(AI_DEAD_ROUTING_KEY);
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

    /** 进度计划判题结果队列：绑定 PLAN 路由键，未绑定前该消息在交换机上不可路由直接丢弃 */
    @Bean
    public Queue reviewPlanQueue() {
        return QueueBuilder.durable(REVIEW_PLAN_QUEUE_NAME).build();
    }

    @Bean
    public Binding reviewPlanBinding() {
        return BindingBuilder
                .bind(reviewPlanQueue())
                .to(reviewExchange())
                .with(PLAN_JUDGE_RECORD_ROUTING_KEY);
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
    @Bean
    public Binding notificationCheckedBinding() {
        return BindingBuilder
                .bind(NotificationQueue())
                .to(NotificationExchange())
                .with(NOTIFICATION_CHECKED_ROUTING_KEY);
    }
    @Bean
    public Binding notificationAppealBinding() {
        return BindingBuilder
                .bind(NotificationQueue())
                .to(NotificationExchange())
                .with(NOTIFICATION_APPEAL_ROUTING_KEY);
    }
    @Bean
    public Binding notificationReviewMasteredBinding() {
        return BindingBuilder
                .bind(NotificationQueue())
                .to(NotificationExchange())
                .with(NOTIFICATION_REVIEW_MASTERED_ROUTING_KEY);
    }

}
