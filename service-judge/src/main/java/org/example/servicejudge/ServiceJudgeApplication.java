package org.example.servicejudge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;

@SpringBootApplication
@MapperScan("org.example.servicejudge.mapper")
@EnableFeignClients(basePackages = {"org.example.serviceapi"})
public class ServiceJudgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceJudgeApplication.class, args);
    }

}
