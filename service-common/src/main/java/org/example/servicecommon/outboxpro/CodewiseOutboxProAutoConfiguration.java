package org.example.servicecommon.outboxpro;

import org.outboxpro.autoconfigure.OutboxProProperties;
import org.outboxpro.spi.deadletter.DeadLetterAlertNotifier;
import org.outboxpro.spi.deadletter.DlqReplayAuthorizer;
import org.outboxpro.spi.transport.MessagePublisher;
import org.outboxpro.transport.rabbit.RabbitMessagePublisher;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * CodeWise 对 OutboxPro 的公共装配：Relay 发布模板 + 死信告警通知器 + 运维端点只读授权边界。
 *
 * <p>仅当宿主服务显式配置 {@code outboxpro.enabled=true}（生产者服务：
 * question/judge/review/community/quiz-nest）时生效；其余服务必须显式配置 false，
 * 因为 OutboxPro 自身的开关是 {@code matchIfMissing=true}。</p>
 *
 * <p>排序说明（与 {@code EventAutoConfiguration}/旧 {@code OutboxAutoConfiguration}
 * 同一类陷阱）：{@code org.example.*} 的自动配置按 FQN 排在 {@code org.outboxpro.*}
 * 之前，OutboxPro 在创建 {@code DeadLetterCoordinator}/{@code DeadLetterAlertTask}/
 * 端点 bean 时通过 {@code ObjectProvider#getIfAvailable} 即时解析 notifier 与
 * authorizer，本配置先行注册的 bean 会被优先采用；这里仍显式声明
 * {@code @AutoConfigureBefore} 兜底，避免依赖字母序。</p>
 */
@AutoConfiguration
@AutoConfigureBefore(org.outboxpro.autoconfigure.OutboxProAutoConfiguration.class)
@ConditionalOnClass(DeadLetterAlertNotifier.class)
@ConditionalOnProperty(prefix = "outboxpro", name = "enabled", havingValue = "true")
public class CodewiseOutboxProAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DeadLetterAlertNotifier.class)
    public CodewiseOutboxAlertNotifier codewiseOutboxAlertNotifier() {
        return new CodewiseOutboxAlertNotifier();
    }

    @Bean
    @ConditionalOnMissingBean(DlqReplayAuthorizer.class)
    public CodewiseDlqReadAuthorizer codewiseDlqReadAuthorizer() {
        return new CodewiseDlqReadAuthorizer();
    }

    /**
     * 覆盖 OutboxPro 默认的 RabbitMQ 发布器，改用「原样字节」模板发送信封。
     *
     * <p>默认实现注入的是应用侧那台模板；它带 {@code Jackson2JsonMessageConverter}（应用侧直发
     * {@code EventEnvelope} 等对象必需），会把已序列化的信封字符串再包一层 JSON 引号，导致
     * 消费端 {@code EnvelopeCodec#unwrap} 解析失败、整条链路的消息被判毒消息丢弃。
     * 详见 {@link OutboxProRelaySupport}。</p>
     *
     * <p>必须经 {@code @AutoConfigureBefore} 先于 {@code OutboxProAutoConfiguration} 注册，
     * 否则它的同名 bean 会先占位、本覆盖不生效（与上面两个 bean 是同一处 seam）。</p>
     */
    @Bean
    @ConditionalOnMissingBean(MessagePublisher.class)
    public MessagePublisher codewiseOutboxProMessagePublisher(ConnectionFactory connectionFactory,
                                                              OutboxProProperties properties) {
        return new RabbitMessagePublisher(
                OutboxProRelaySupport.relayRabbitTemplate(connectionFactory),
                properties.getProducer().getConfirmTimeout().toMillis());
    }
}