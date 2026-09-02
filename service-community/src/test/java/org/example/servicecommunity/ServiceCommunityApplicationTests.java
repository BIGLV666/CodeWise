package org.example.servicecommunity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 全上下文装配冒烟测试：依赖基础设施与密钥环境变量，默认跳过，
 * 以 {@code CODEWISE_INFRA=true} 显式启用（见 ServiceQuestionApplicationTests 注释）。
 */
@EnabledIfEnvironmentVariable(named = "CODEWISE_INFRA", matches = "true")
@SpringBootTest
class ServiceCommunityApplicationTests {

    @Test
    void contextLoads() {
    }

}
