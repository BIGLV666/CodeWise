package com.example.servicequiznest;

import com.example.servicequiznest.support.TestContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用 <b>生产 application.yaml</b>（不加 test profile）启动上下文：验证服务本身可启动。
 *
 * <p>历史状态是「启动必失败」：yaml 没有 {@code spring.config.import} 时 nacos-config 直接
 * 让上下文崩，OutboxPro 又默认开启（默认 poll-interval "1000ms" 在 Boot 3.2.4 上同样失败）。
 * 这里只把外部依赖（MySQL/Redis/Nacos 地址）换成容器与占位值，其余全部走仓库里的真实配置。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "CODEWISE_INTERNAL_TOKEN=test-token"
})
class ProductionConfigContextTest {

    @Container
    static final MySQLContainer<?> MYSQL = TestContainers.newMySql();

    @Container
    static final GenericContainer<?> REDIS = TestContainers.newRedis();

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        TestContainers.registerMySql(registry, MYSQL);
        TestContainers.registerRedis(registry, REDIS);
    }

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("生产配置可启动：端口 8088、OutboxPro/MQ 开启、限流开启、内部 Token 生效")
    void productionConfigStarts() {
        assertThat(environment.getProperty("server.port")).isEqualTo("8088");
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("service-quiz-nest");
        // 邀请码经 OutboxPro 投递，quiz-nest 是生产者；poll-interval 必须是纯毫秒
        assertThat(environment.getProperty("outboxpro.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("outboxpro.producer.poll-interval")).isEqualTo("1000");
        // 需要 MqConfig 声明 notification.exchange/queue/binding
        assertThat(environment.getProperty("codewise.mq.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("codewise.internal-token")).isEqualTo("test-token");
        assertThat(environment.getProperty("api.governance.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("api.governance.management.base-path")).isEqualTo("/api/quiz/governance");
    }
}