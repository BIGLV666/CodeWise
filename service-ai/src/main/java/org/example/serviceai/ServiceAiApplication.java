package org.example.serviceai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = {"org.example.serviceapi"})
public class ServiceAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceAiApplication.class, args);
    }

}
