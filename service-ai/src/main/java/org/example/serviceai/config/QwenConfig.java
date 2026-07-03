package org.example.serviceai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "qianwen")
@Data
public class QwenConfig {
    private String model;
    private String apikey;
    private String endpoint ;
    private Integer maxTokens ;
    private Integer timeout ;
    private Double temperature;
}