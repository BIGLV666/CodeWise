package org.example.serviceuser;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
public class ServiceUserApplication {


    public static void main(String[] args) {
        SpringApplication.run(ServiceUserApplication.class, args);

    }

}
