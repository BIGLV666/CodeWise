package com.example.servicequiznest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * service-quiz-nest 启动类。
 *
 * <p>发送邀请码时经 {@code UserFeignClient} 取邀请人昵称，因此必须显式开启
 * Feign 客户端扫描（service-common 的 {@code AdminAuthAspect} 也依赖同一个客户端）。</p>
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "org.example.serviceapi")
public class ServiceQuizNestApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceQuizNestApplication.class, args);
    }

}
