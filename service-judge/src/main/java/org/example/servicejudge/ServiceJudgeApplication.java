package org.example.servicejudge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("org.example.servicejudge.mapper")
public class ServiceJudgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceJudgeApplication.class, args);
    }

}
