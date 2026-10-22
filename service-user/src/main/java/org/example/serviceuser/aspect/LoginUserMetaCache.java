package org.example.serviceuser.aspect;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.UserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录账号元数据缓存：把「账号 → userId/status」放进 Redis，失败链路不再每次都查库。
 *
 * <p>缓存值格式为 {@code userId|status}；账号不存在时写 {@code 0} 作为负缓存，
 * 防止随机用户名把数据库打穿。正缓存 10 分钟、负缓存 60 秒，账号状态变更最迟
 * 一个 TTL 后生效；Redis 故障时自动降级为直接查库，不阻塞登录。</p>
 */
@Slf4j
@Component
public class LoginUserMetaCache {

    /** 正缓存 TTL：登录态/封禁状态变更最多延迟 10 分钟。 */
    static final Duration POSITIVE_TTL = Duration.ofMinutes(10);

    /** 负缓存 TTL：只挡住短时间内的随机账号探测。 */
    static final Duration NEGATIVE_TTL = Duration.ofSeconds(60);

    private static final String NOT_FOUND_VALUE = "0";

    private final StringRedisTemplate stringRedisTemplate;
    private final UserMapper userMapper;

    public LoginUserMetaCache(StringRedisTemplate stringRedisTemplate, UserMapper userMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.userMapper = userMapper;
    }

    /**
     * 读取账号元数据：先查 Redis，未命中才回库并回填缓存。
     *
     * @param account 用户名或邮箱（与登录入口的入参一致）
     * @return 元数据；账号不存在返回 {@link LoginUserMeta#NOT_FOUND}
     */
    public LoginUserMeta get(String account) {
        if (account == null || account.isBlank()) {
            return LoginUserMeta.NOT_FOUND;
        }
        String key = RedisContext.loginUserMetaKey(account);
        String cached = readQuietly(key);
        if (cached != null) {
            LoginUserMeta meta = parse(cached);
            if (meta != null) {
                return meta;
            }
            // 值格式异常（历史脏数据/人工写入）：回库覆盖，避免一直解析失败。
            log.warn("登录账号元数据缓存格式异常，回源查询 account={} value={}", account, cached);
        }

        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_name", account));
        if (user == null) {
            user = userMapper.selectOne(new QueryWrapper<User>().eq("email", account));
        }
        if (user == null) {
            writeQuietly(key, NOT_FOUND_VALUE, NEGATIVE_TTL);
            return LoginUserMeta.NOT_FOUND;
        }
        LoginUserMeta meta = new LoginUserMeta(user.getUserId(), user.getStatus());
        writeQuietly(key, serialize(meta), POSITIVE_TTL);
        return meta;
    }

    /**
     * 登录成功后回填缓存。用户名与邮箱都写一份，下一次无论用哪种账号登录都能命中。
     *
     * @param userId   用户 ID
     * @param status   账号状态
     * @param accounts 用户名、邮箱等登录账号
     */
    public void put(Long userId, Integer status, String... accounts) {
        if (userId == null || accounts == null) {
            return;
        }
        String value = serialize(new LoginUserMeta(userId, status));
        for (String account : accounts) {
            if (account == null || account.isBlank()) {
                continue;
            }
            writeQuietly(RedisContext.loginUserMetaKey(account), value, POSITIVE_TTL);
        }
    }

    private String readQuietly(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            // Redis 故障时降级查库：宁可多一次 DB 查询，也不能让正常登录失败。
            log.warn("读取登录账号元数据缓存失败，降级查库 key={}", key, e);
            return null;
        }
    }

    private void writeQuietly(String key, String value, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("写入登录账号元数据缓存失败 key={}", key, e);
        }
    }

    private String serialize(LoginUserMeta meta) {
        int status = meta.status() == null ? -1 : meta.status();
        return meta.userId() + "|" + status;
    }

    private LoginUserMeta parse(String value) {
        if (NOT_FOUND_VALUE.equals(value)) {
            return LoginUserMeta.NOT_FOUND;
        }
        int separator = value.indexOf('|');
        if (separator <= 0) {
            return null;
        }
        try {
            Long userId = Long.valueOf(value.substring(0, separator));
            Integer status = Integer.valueOf(value.substring(separator + 1));
            return new LoginUserMeta(userId, status);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
