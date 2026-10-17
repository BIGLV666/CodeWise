package org.example.servicecommon.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 基础设施装配（RedisTemplate 的 key/值序列化策略）。
 *
 * <p>Key 用 {@code StringRedisSerializer}：跨服务/跨脚本（如 UserAuthInterceptor 的
 * 凭证失效 Lua）按裸字符串拼 key，值序列化策略只影响 value，不要改成 Jackson
 * key 序列化，否则脚本里的 KEYS 会对不上。</p>
 *
 * <p>TODO(可用性): 未配置 {@code spring.data.redis.timeout}，Redis 故障时每个命令要等
 * Lettuce 默认 60s 命令超时才降级（实测一次请求经"拦截器 + 限流"两处 = 120s 才放行）。
 * 限流与凭证失效目前都是 fail-open（可用性优先），建议给各服务补
 * {@code spring.data.redis.timeout: 1s~2s}（或 Lettuce shutdown/超时参数），
 * 让降级在秒级完成，而不是把每个请求拖到分钟级。</p>
 */
@AutoConfiguration
@ConditionalOnClass(RedisTemplate.class)
@ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Key 序列化
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Value 序列化 - 支持 LocalDateTime
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());   // ← 关键：注册时间模块
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.activateDefaultTyping(
                objectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }
}
