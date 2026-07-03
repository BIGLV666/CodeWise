package org.example.serviceemail;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;

@SpringBootApplication
@Import(org.example.servicecommon.config.MqConfig.class)
public class ServiceEmailApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceEmailApplication.class, args);
    }
    @Bean
    public ApplicationRunner checkMailConfig(JavaMailSender mailSender) {
        return args -> {
            System.out.println("✅ JavaMailSender Bean 加载成功: " + mailSender);
        };
    }
}
