package org.example.servicejudge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("org.example.servicejudge.mapper")
@EnableFeignClients(basePackages = {"org.example.serviceapi"})
@EnableAsync
@EnableScheduling
public class ServiceJudgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceJudgeApplication.class, args);
    }

}
