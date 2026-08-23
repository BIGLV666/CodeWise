package org.example.servicemessage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = "org.example.serviceapi.feign")
public class ServiceMessageApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceMessageApplication.class, args);
    }

}
