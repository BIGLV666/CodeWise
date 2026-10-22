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
 * <p><b>可用性约定：</b>所有使用 Redis 的服务（user/question/review/community/message/ai/judge）
 * 必须在本地 {@code application.y*ml} 显式配置 {@code spring.data.redis.timeout: 2s} 与
 * {@code spring.data.redis.connect-timeout: 2s}。不配时 Lettuce 命令超时默认 60s，
 * 而每个带凭证的请求要经过「拦截器凭证失效 Lua + 限流」两次 Redis 调用，Redis 故障时
 * 单请求会被拖到 ~120s 才降级。这两项同样被 redisson-spring-boot-starter 读取为
 * {@code setTimeout} / {@code setConnectTimeout}，无需单独的 Redisson 配置。
 * 限流与凭证失效都是 fail-open（可用性优先），2s 内降级即可。</p>
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
