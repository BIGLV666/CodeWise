package org.example.servicereview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = {"org.example.serviceapi"})
public class ServiceReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceReviewApplication.class, args);
    }

}
