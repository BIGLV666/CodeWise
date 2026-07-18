package org.example.serviceai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableFeignClients(basePackages = {"org.example.serviceapi"})
@MapperScan("org.example.serviceai.mapper")
public class ServiceAiApplication {


    public static void main(String[] args) {
        SpringApplication.run(ServiceAiApplication.class, args);

    }

}
