package org.example.serviceuser;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * 用户服务启动类。
 * <p>启用 service-api 的 Feign 契约：service-common 的 AdminAuthAspect 依赖
 * {@code UserFeignClient} 回查用户角色，未启用时管理接口鉴权会因依赖缺失而失败。</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "org.example.serviceapi")
public class ServiceUserApplication {


    public static void main(String[] args) {
        SpringApplication.run(ServiceUserApplication.class, args);

    }

}
