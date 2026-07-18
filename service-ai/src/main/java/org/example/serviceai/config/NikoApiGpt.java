package org.example.serviceai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "nikoapigpt")
@Data
public class NikoApiGpt {
    String apikey ;
    String model ;

}
