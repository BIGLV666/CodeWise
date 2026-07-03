package org.example.servicequestion;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableFeignClients(basePackages = {"org.example.serviceapi"})
@EnableAsync
@EnableScheduling
@MapperScan("org.example.servicequestion.mapper")
public class ServiceQuestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceQuestionApplication.class, args);
    }

}
