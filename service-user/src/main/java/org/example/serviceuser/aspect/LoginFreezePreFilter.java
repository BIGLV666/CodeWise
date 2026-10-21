package org.example.serviceuser.aspect;

import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PreFilter;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * 登录前置冻结校验：密码校验前先看 {@code login:freeze:<userId>}，冻结期内直接拒绝。
 *
 * <p>登录接口在 {@code UserAuthInterceptor} 的白名单内且不携带 token，
 * {@code revoke_before} 拦不到 login，所以必须在这里读取冻结 key。账号元数据走
 * {@link LoginUserMetaCache}，热路径只有两次 Redis 读；Redis/DB 异常一律放行，
 * 避免风控组件故障把正常登录全部锁死。</p>
 */
@Slf4j
@Component
@Order(300)
public class LoginFreezePreFilter implements PreFilter {

    /** 覆盖用户名/密码登录与邮箱/密码登录，防止冻结后切换入口绕过。 */
    static final Set<String> LOGIN_METHODS = Set.of("login", "emailLogin");

    private final LoginUserMetaCache metaCache;
    private final StringRedisTemplate stringRedisTemplate;

    public LoginFreezePreFilter(LoginUserMetaCache metaCache,
                                StringRedisTemplate stringRedisTemplate) {
        this.metaCache = metaCache;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean doFilter(FilterContext context) {
        if (context == null || context.getMethod() == null) {
            return true;
        }
        if (!LOGIN_METHODS.contains(context.getMethod().getName())) {
            return true;
        }
        String account = firstStringArgument(context.getArgs());
        if (account == null) {
            return true;
        }

        try {
            LoginUserMeta meta = metaCache.get(account);
            if (meta == null || !meta.exists()) {
                return true;
            }
            // 已封禁/已注销账号交给 UserService 返回原本的封禁/注销文案，
            // 不要用「临时冻结」覆盖管理员处置结果。
            if (meta.status() != null && (meta.status() == 0 || meta.status() == 2)) {
                return true;
            }
            String freezeUntil = stringRedisTemplate.opsForValue()
                    .get(RedisContext.loginFreezeKey(meta.userId()));
            if (freezeUntil == null) {
                return true;
            }
            long remainingSeconds = Long.parseLong(freezeUntil) - Instant.now().getEpochSecond();
            if (remainingSeconds <= 0) {
                // TTL 与值理论上同步过期，这里兜底处理时钟边界。
                return true;
            }

            context.setRejectStatus(429);
            context.setRejectReason("账号因多次密码错误已被临时冻结，请 "
                    + formatRemaining(remainingSeconds) + "后再试");
            log.warn("登录前置校验拒绝：账号处于冻结期 account={} userId={} remainingSeconds={}",
                    account, meta.userId(), remainingSeconds);
            return false;
        } catch (Exception e) {
            // 风控组件异常不能影响正常登录：放行并记录，由后置链路继续观察。
            log.warn("登录前置冻结校验异常，放行 account={}", account, e);
            return true;
        }
    }

    @Override
    public int getOrder() {
        return 300;
    }

    private String firstStringArgument(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return null;
        }
        String account = String.valueOf(args[0]);
        return account.isBlank() ? null : account;
    }

    /** 把剩余秒数转成用户可读文案，向上取整，避免出现「请 0 分钟后再试」。 */
    static String formatRemaining(long seconds) {
        if (seconds >= 86400) {
            return ((seconds + 86399) / 86400) + " 天";
        }
        if (seconds >= 3600) {
            return ((seconds + 3599) / 3600) + " 小时";
        }
        if (seconds >= 60) {
            return ((seconds + 59) / 60) + " 分钟";
        }
        return Math.max(1, seconds) + " 秒";
    }
}
