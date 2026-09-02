package org.example.servicecommon.outboxpro;

import org.awaitility.Awaitility;
import org.example.servicecommon.event.EnvelopeCodec;
import org.junit.jupiter.api.Test;
import org.outboxpro.core.OutboxProPublisher;
import org.outboxpro.core.context.EventContext;
import org.outboxpro.core.envelope.EventEnvelope;
import org.outboxpro.core.event.EventDefinition;
import org.outboxpro.core.handler.OutboxProHandler;
import org.outboxpro.core.subscription.EventBinding;
import org.outboxpro.core.subscription.OutboxProSubscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OutboxPro 1.1.0 与 CodeWise（Spring Boot 3.2.4）的兼容性守卫测试。
 *
 * <p>用真实 MySQL 8.4 + RabbitMQ 3.13 容器走通完整链路：事务内 publish 落
 * {@code outboxpro_outbox}（与业务同事务）→ Relay 认领并经 Publisher Confirm
 * 发往 RabbitMQ（状态 SENT）→ RELIABLE 消费者执行 Handler 并落 Inbox 幂等记录。
 * 本地无 Docker 时整类自动跳过。</p>
 *
 * <p>消费端 wire 兼容另见本包 {@link OutboxWireCompatTest}（验证 CodeWise 消费者
 * 的 EnvelopeCodec 能解析 OutboxPro 消息体）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {OutboxProIntegrationTest.TestApp.class, OutboxProIntegrationTest.TestBeans.class})
class OutboxProIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    static final String EVENT_TYPE = "test.event";
    static final String EXCHANGE = "test.exchange";
    static final String ROUTING_KEY = "test.routing";
    static final String QUEUE = "test.queue";

    /** Handler 收到的载荷（RELIABLE 幂等后每条只消费一次） */
    static final ConcurrentLinkedQueue<String> RECEIVED = new ConcurrentLinkedQueue<>();

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getAmqpPort());
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        // service-common 自身自动配置所需
        registry.add("codewise.internal-token", () -> "test-token");
        // 兼容性：OutboxPro 默认 "1000ms" 需要 Spring Framework 6.2 的 duration 解析，
        // Boot 3.2.4（Framework 6.1）只认纯毫秒/ISO-8601，显式覆盖为纯毫秒
        registry.add("outboxpro.producer.poll-interval", () -> "1000");
    }

    @SpringBootApplication
    static class TestApp {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        EventDefinition<TestPayload> testEventDefinition() {
            return EventDefinition.<TestPayload>builder()
                    .eventType(EVENT_TYPE)
                    .payloadType(TestPayload.class)
                    .route(EXCHANGE, ROUTING_KEY)
                    .build();
        }

        @Bean
        OutboxProSubscription testSubscription() {
            return OutboxProSubscription.builder()
                    .name("test-sub")
                    .exchange(EXCHANGE)
                    .queue(QUEUE)
                    .bindings(EventBinding.reliable(EVENT_TYPE, ROUTING_KEY, TestPayload.class))
                    .build();
        }

        @Bean
        OutboxProHandler<TestPayload> testHandler() {
            return new OutboxProHandler<>() {
                @Override public String eventType() { return EVENT_TYPE; }
                @Override public Class<TestPayload> payloadType() { return TestPayload.class; }
                @Override public void handle(EventContext<TestPayload> context) {
                    RECEIVED.add(context.getPayload().value);
                }
            };
        }
    }

    /** 载荷 POJO：与业务 DTO 同构的小对象 */
    static class TestPayload {
        public String value;
        public TestPayload() { }
        public TestPayload(String value) { this.value = value; }
    }

    @Autowired OutboxProPublisher publisher;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void outboxProSchemaTablesAreCreated() {
        for (String table : new String[]{"outboxpro_outbox", "outboxpro_inbox", "outboxpro_message_log",
                "outboxpro_dead_letter", "outboxpro_dead_letter_counter"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                    Integer.class, MYSQL.getDatabaseName(), table);
            assertThat(count).as("table %s should exist", table).isEqualTo(1);
        }
    }

    @Test
    void publishInTransactionRelayThenReliableConsume() {
        // 事务内发布：与业务写入同事务（此处以 TransactionTemplate 代表业务的 @Transactional）
        EventEnvelope<TestPayload> envelope = transactionTemplate.execute(status ->
                publisher.publish(EVENT_TYPE, new TestPayload("hello-outboxpro")));
        String eventId = envelope.getEventId();

        // 尚未 Relay：应为 PENDING
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status FROM outboxpro_outbox WHERE event_id = ?", eventId);
        assertThat(row.get("status")).isEqualTo("PENDING");

        // 调度 Relay（默认 1s 轮询）：认领 → Publisher Confirm 发送 → SENT
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<String, Object> sent = jdbcTemplate.queryForMap(
                    "SELECT status, sent_time FROM outboxpro_outbox WHERE event_id = ?", eventId);
            assertThat(sent.get("status")).isEqualTo("SENT");
            assertThat(sent.get("sent_time")).isNotNull();
        });

        // RELIABLE 消费：Handler 执行 + Inbox SUCCESS（幂等键 consumer_name + event_id）
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(RECEIVED).containsExactly("hello-outboxpro"));
        Integer inbox = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outboxpro_inbox WHERE consumer_name = 'test-sub' AND event_id = ? AND status = 'SUCCESS'",
                Integer.class, eventId);
        assertThat(inbox).isEqualTo(1);
    }
}
