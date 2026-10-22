package com.example.servicequiznest;

import com.example.servicequiznest.support.AbstractGroupIntegrationTest;
import com.example.servicequiznest.support.TestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * 上下文冒烟测试：原来只加 {@code @SpringBootTest} 会因缺少数据源/nacos 配置直接启动失败
 * （quiz-nest 还没接入 deploy 的 Nacos 配置），因此改为复用集成测试基类的真 MySQL 容器。
 */
class ServiceQuizNestApplicationTests extends AbstractGroupIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = TestContainers.newMySql();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        TestContainers.registerMySql(registry, MYSQL);
    }

    @Test
    void contextLoads() {
    }
}
