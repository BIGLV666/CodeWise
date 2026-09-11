package org.example.serviceai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "glm")
@Data
public class GLMConfig {
    String apikey;
    String model;
    /** OpenAI 兼容的 chat/completions 完整地址；默认智谱官方，可经 Nacos 覆盖为自建网关。 */
    String endpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
}
