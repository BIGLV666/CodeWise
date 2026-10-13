package org.example.serviceuser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 全上下文装配冒烟测试：依赖 Nacos/Redis/RabbitMQ/MySQL 基础设施与
 * CODEWISE_INTERNAL_TOKEN 等密钥。默认跳过；具备基础设施时以
 * {@code CODEWISE_INFRA=true}（含所需环境变量）显式启用。
 *
 * <p>注意测试类必须与其 {@code @SpringBootConfiguration} 同包（或用 {@code classes}
 * 显式指定），否则 Spring 自举时向上找不到启动类，直接报
 * {@code Unable to find a @SpringBootConfiguration}——本类此前放在默认包里、
 * 且用 {@code @EnableAutoConfiguration} 试图绕过，导致该测试永远失败。</p>
 */
@EnabledIfEnvironmentVariable(named = "CODEWISE_INFRA", matches = "true")
@SpringBootTest
class ServiceUserApplicationTests {

    @Test
    void contextLoads() {
    }
}
