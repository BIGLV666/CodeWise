package org.example.servicegateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 全上下文装配冒烟测试：依赖 Nacos 与密钥环境变量（JWT_SECRET 等），默认跳过，
 * 以 {@code CODEWISE_INFRA=true} 显式启用（见 ServiceQuestionApplicationTests 注释）。
 */
@EnabledIfEnvironmentVariable(named = "CODEWISE_INFRA", matches = "true")
@SpringBootTest
class ServiceGatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}
