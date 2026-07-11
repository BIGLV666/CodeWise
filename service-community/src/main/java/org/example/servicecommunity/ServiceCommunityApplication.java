package org.example.servicecommunity;

import org.apache.logging.log4j.message.AsynchronouslyFormattable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableFeignClients(basePackages = {"org.example.serviceapi"})
@EnableScheduling
@MapperScan("org.example.servicecommunity.mapper")
public class ServiceCommunityApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceCommunityApplication.class, args);
    }

}
