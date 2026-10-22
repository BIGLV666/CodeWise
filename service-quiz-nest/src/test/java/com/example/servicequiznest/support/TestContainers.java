package com.example.servicequiznest.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * 集成测试用的容器工厂：真 MySQL 8.4（业务数据）+ 真 Redis 7（token 幂等凭证）。
 *
 * <p>MySQL 用 main 里真实的 {@code sql/quiz.nest.sql} 初始化（建表脚本只有一份，测试与生产同源），
 * 库名沿用脚本里的 {@code codewise_quiz_nest}，保证 JDBC 连接与脚本 USE 的库一致。</p>
 *
 * <p>每个测试类各自 {@code newContainer()}：Testcontainers 的 JUnit 扩展在
 * {@code afterAll} 会停容器，跨类共享同一个静态容器会因“停掉后再 start”而失败。</p>
 *
 * <p>本机跑测试的两个环境前提（都不是代码问题）：</p>
 * <ol>
 *   <li>docker daemon 最低 API 版本高于 testcontainers 内嵌 docker-java 的默认值（1.32）时，
 *       由 pom 的 {@code docker.api.version} 属性经 surefire 注入 {@code -Dapi.version}；</li>
 *   <li>无法访问 docker.io 时需 {@code TESTCONTAINERS_RYUK_DISABLED=true}（ryuk 镜像拉不下来，
 *       否则容器起不来、测试会被静默跳过）。</li>
 * </ol>
 */
public final class TestContainers {

    private static final int REDIS_PORT = 6379;

    private TestContainers() {
    }

    public static MySQLContainer<?> newMySql() {
        return new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("codewise_quiz_nest")
                .withInitScript("sql/quiz.nest.sql");
    }

    public static GenericContainer<?> newRedis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);
    }

    public static void registerMySql(DynamicPropertyRegistry registry, MySQLContainer<?> mysql) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    public static void registerRedis(DynamicPropertyRegistry registry, GenericContainer<?> redis) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(REDIS_PORT));
    }
}
