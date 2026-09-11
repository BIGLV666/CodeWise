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
    /** OpenAI 兼容的 chat/completions 完整地址；默认 nikoapi 公共网关，可经 Nacos 覆盖为自建网关。 */
    String endpoint = "https://nikoapi.xyz/v1/chat/completions";

}
