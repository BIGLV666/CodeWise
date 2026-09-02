package org.example.servicejudge;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * 上下文装配冒烟测试。
 *
 * <p>本测试只验证 Bean 装配（controller -> 门面 -> 容器池 -> Docker 模板、
 * 消费者 -> handler -> 任务服务等），不依赖本机 Docker/Redis：
 * {@code DockerConfig} 出于安全会拒绝明文 TCP 的 {@code docker.host}，
 * Redisson 启动即建立真实连接。以 {@code @MockBean} 替换这两个基础客户端
 * 后，真实 Bean 定义（DockerConfig / RedissonAutoConfiguration 工厂）不会被
 * 调用，容器池预热的逐容器失败也会被 init 内部捕获记录。</p>
 *
 * <p>仍需 CODEWISE_INTERNAL_TOKEN 等密钥环境变量：默认跳过，
 * 以 {@code CODEWISE_INFRA=true} 显式启用（见 ServiceQuestionApplicationTests 注释）。</p>
 */
@EnabledIfEnvironmentVariable(named = "CODEWISE_INFRA", matches = "true")
@SpringBootTest
class ServiceJudgeApplicationTests {

    @MockBean
    private DockerClient dockerClient;

    @MockBean
    private RedissonClient redisson;

    @Test
    void contextLoads() {
    }

}

