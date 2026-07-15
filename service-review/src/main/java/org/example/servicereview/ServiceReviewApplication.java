package org.example.servicereview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients(basePackages = {"org.example.serviceapi"})
@EnableAsync
@EnableScheduling
public class ServiceReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceReviewApplication.class, args);
    }

}
