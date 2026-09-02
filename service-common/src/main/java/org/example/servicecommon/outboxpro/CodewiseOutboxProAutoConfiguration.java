package org.example.servicecommon.outboxpro;

import org.outboxpro.spi.deadletter.DeadLetterAlertNotifier;
import org.outboxpro.spi.deadletter.DlqReplayAuthorizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * CodeWise 对 OutboxPro 的公共装配：死信告警通知器 + 运维端点只读授权边界。
 *
 * <p>仅当宿主服务显式配置 {@code outboxpro.enabled=true}（生产者服务：
 * question/judge/review/community）时生效；其余服务必须显式配置 false，
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
}
