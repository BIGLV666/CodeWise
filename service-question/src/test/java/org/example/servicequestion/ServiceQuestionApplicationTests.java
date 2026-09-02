package org.example.servicequestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 全上下文装配冒烟测试：依赖 Nacos/Redis/RabbitMQ/MySQL 基础设施与
 * CODEWISE_INTERNAL_TOKEN 等密钥。默认跳过；具备基础设施时以
 * {@code CODEWISE_INFRA=true}（含所需环境变量）显式启用。
 */
@EnabledIfEnvironmentVariable(named = "CODEWISE_INFRA", matches = "true")
@SpringBootTest
class ServiceQuestionApplicationTests {

    @Test
    void contextLoads() {

    }

}
