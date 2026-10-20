package org.example.serviceuser.aspect;



import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import io.github.biglv666.apigovernance.async.event.AsyncError;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;

import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.service.EmailService;

import org.example.serviceuser.entry.User;
import org.example.serviceuser.exception.UserErrorCode;
import org.example.serviceuser.mapper.UserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;


/**
 * 该类为用户保护类，借助biglv的异步处理框架，捕获方法内抛出的异常，如果一个账户多次登录失败需要冻结账号
 * 可以采用时间退避
 * 1 30s
 * 2 1 min
 * 3 5 min
 * 4 10 min
 * <P>TODO 登录前置冻结校验：当前只写入 login:freeze 与 revoke_before，尚未在登录入口读取 login:freeze。</P>
 * <P>登录接口在 {@link org.example.servicecommon.config.UserAuthInterceptor} 的 PUBLIC_PATHS 内且不携带 token，
 * revoke_before 拦不到 login，冻结期间仍可登录成功（成功还会清掉冻结 key）。</P>
 * <P>建议在 {@code UserService.login} 校验密码前读取 login:freeze，未到期直接抛登录风控错误码（4290102）；
 * 或新增独立的登录前置拦截器统一覆盖三个登录入口。</P>
 */
@Slf4j
@Component
public class AccountProtectionAsyncHandlers{
    /** 与 {@code UserController.login} 上的 @AsyncAction 保持一致，否则 handler 不会被执行。 */
    private static final String LOGIN_ACTION = "user-login";
    /** 3 分钟滑动窗口。 */
    private static final long WINDOW_MILLIS = 3 * 60 * 1000L;
    /** 窗口内允许的最大失败次数，第 5 次触发风控。 */
    private static final int MAX_ATTEMPTS = 5;
    /** 风控次数保留 24 小时，每次触发续期，成功登录立即删除。 */
    private static final long RISK_COUNT_TTL_SECONDS = 24 * 60 * 60L;
    /** 冻结档位：30s / 1m / 5m / 10m / 30m / 1h / 6h / 12h / 1d，之后恒为 1d。 */
    private static final String FREEZE_STEPS_SECONDS = "30,60,300,600,1800,3600,21600,43200,86400";

    /**
     * 登录失败风控脚本：滑动窗口计数 + 风控次数递增 + 写失效线和冻结 key，全部在一次 EVAL 内原子完成。
     *
     * <pre>
     * KEYS[1] = login:fail:window:&lt;userId&gt;       （ZSET）
     * KEYS[2] = login:risk:count:&lt;userId&gt;        （STRING）
     * KEYS[3] = revoke_before:&lt;userId&gt;            （STRING，epoch 秒）
     * KEYS[4] = login:freeze:&lt;userId&gt;             （STRING，冻结到期 epoch 秒）
     *
     * ARGV[1] = 本次请求的 member 唯一标识
     * ARGV[2] = 窗口毫秒数
     * ARGV[3] = 触发阈值（第 N 次触发）
     * ARGV[4] = 风控次数 TTL 秒
     * ARGV[5] = 冻结档位 CSV
     * ARGV[6] = 失效线 TTL 秒
     *
     * 返回 {是否触发, 本次窗口失败次数, 本次冻结秒数, 风控次数}
     * </pre>
     *
     * <p>触发后删除滑动窗口：一次风控只由一批失败触发一次，后续失败重新累计，
     * 避免攻击者持续刷请求把档位瞬间推到最高档。</p>
     */
    static final String LOGIN_FAILURE_SCRIPT_TEXT = """
            local time = redis.call('TIME')
            local nowMillis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local windowMillis = tonumber(ARGV[2])

            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMillis - windowMillis)
            redis.call('ZADD', KEYS[1], nowMillis, ARGV[1])
            redis.call('PEXPIRE', KEYS[1], windowMillis + 1000)

            local attempts = redis.call('ZCARD', KEYS[1])
            if attempts < tonumber(ARGV[3]) then
                return {0, attempts, 0, 0}
            end

            local riskCount = redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4]))

            local steps = {}
            for step in string.gmatch(ARGV[5], '([^,]+)') do
                steps[#steps + 1] = tonumber(step)
            end
            local freezeSeconds = steps[math.min(riskCount, #steps)]

            -- 失效线的值必须是「当前 epoch 秒」：拦截器用 token 的 iat 与它比较，
            -- 写未来时间会让冻结期间新签发的 token 也被判定失效。
            redis.call('SET', KEYS[3], time[1], 'EX', tonumber(ARGV[6]))
            redis.call('SET', KEYS[4], tostring(tonumber(time[1]) + freezeSeconds), 'EX', freezeSeconds)
            redis.call('DEL', KEYS[1])

            return {1, attempts, freezeSeconds, riskCount}
            """;

    /**
     * 登录成功清理脚本：只清滑动窗口、风控次数和冻结 key。
     *
     * <p>不要删除 {@code revoke_before:<userId>}：它与管理员封禁、改密共用，
     * 删掉会让封禁/改密期间未使用过的旧 token 复活；新登录 token 的 iat 更晚，天然通过校验。</p>
     */
    static final String LOGIN_SUCCESS_SCRIPT_TEXT = """
            redis.call('DEL', KEYS[1])
            redis.call('DEL', KEYS[2])
            redis.call('DEL', KEYS[3])
            return 1
            """;

    private static final RedisScript<List> LOGIN_FAILURE_SCRIPT =
            new DefaultRedisScript<>(LOGIN_FAILURE_SCRIPT_TEXT, List.class);
    private static final RedisScript<Long> LOGIN_SUCCESS_SCRIPT =
            new DefaultRedisScript<>(LOGIN_SUCCESS_SCRIPT_TEXT, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final UserMapper userMapper;
    private final EmailService emailService;

    public AccountProtectionAsyncHandlers(
            StringRedisTemplate stringRedisTemplate,
            UserMapper userMapper,
            EmailService emailService
    ){
        // Lua 里的 GET 需要用 tonumber 解析失效线；RedisTemplate<String,Object> 的 Jackson
        // 值序列化器会把数字写成带引号的字符串，这里必须使用 StringRedisTemplate。
        this.stringRedisTemplate = stringRedisTemplate;
        this.userMapper = userMapper;
        this.emailService = emailService;
    }



    /**
     * 登录成功后需要清除用户的失败记录。不可累加
     * @param event 元数据，风控账号所需要的userid可以在此取
     * <p>为什么不在后置过滤处理该问题</p>
     *  1.如果Aop捕获业务的异常的确可以发现同一个账户被爆破，但是缺少用户元数据，因为抛出了异常无法获取用户数据也不能精准风控，只能依靠ip</p>
     *  <p>2. 在用户多被风控时候我们应该取发送邮件提醒，所以必须元数据，即可利用biglv的框架来做更精准的异步处理链</P>
     *  <P>注意：该操作是异步处理，也就是如果高强度的爆破可能存在ms级误差，也就是假如在异步线程处理时可能会有请求已经在处理，所以实际请求的数量
     *  可能大于理性风控数</P>
     */

    @AsyncHandler(value = LOGIN_ACTION, phase = AsyncPhase.AFTER_SUCCESS, order = 200)
    public void LoginSuccess(AsyncEvent event) {
        Map<String,Object>body=event.data();
        Long userId=asLong(body.get("userId"));
        String userName=asString(body.get("username"));
        if(userId==null){
            log.info("用户Id为空，用户名 {}",userName);
            return;
        }
        //使用lua脚本清空用户目前的风控记录
        try {
            stringRedisTemplate.execute(
                    LOGIN_SUCCESS_SCRIPT,
                    List.of(
                            RedisContext.loginFailWindowKey(userId),
                            RedisContext.loginRiskCountKey(userId),
                            RedisContext.loginFreezeKey(userId)
                    )
            );
        } catch (Exception e) {
            // 风控清理失败不能影响登录结果，只记录日志；下一次登录成功仍会重试。
            log.error("清理登录风控记录失败 userId={} username={}", userId, userName, e);
        }
    }

    @AsyncHandler(value = LOGIN_ACTION, phase = AsyncPhase.AFTER_ERROR)
    public void loginFailure(AsyncEvent event) {
        AsyncError error = event.error();
        //错误类型不为登录失败，跳过
        if(error == null || !UserErrorCode.LOGIN_FAILED.getMessage().equals(error.message()))return;

        /**
          使用lua脚本原子滑动窗口原子加一，当到达风控次数时将用户id移入 USER_TOKEN_REVOKE_BEFORE 桶
          采用和封禁同样的桶，这样在{@link org.example.servicecommon.config.UserAuthInterceptor}即可完成校验，
          时间设置为当前应该冻结的时间即可
         */
        String userName = asString(event.data().get("username"));
        if (userName == null || userName.isBlank()) {
            // 失败事件没有返回结果，用户名由 AsyncEnricher 从方法参数补充；缺失时无法定位账号。
            log.warn("登录失败事件缺少用户名，跳过风控 eventId={}", event.id());
            return;
        }

        User user;
        try {
            user = findUserByAccount(userName);
        } catch (Exception e) {
            // 异步风控失败不应影响登录响应；数据库异常只记日志，交由后续请求重试累计。
            log.error("登录失败风控查询用户异常 username={}", userName, e);
            return;
        }
        if (user == null || user.getUserId() == null) {
            // 账号不存在时没有 userId，无法写用户级失效线；不存在账号的爆破交给 IP 维度限流。
            return;
        }
        // 已封禁 / 已注销账号不再参与登录风控，避免覆盖管理员写入的失效线。
        if (user.getStatus() != null && (user.getStatus() == 0 || user.getStatus() == 2)) {
            return;
        }
        Long userId = user.getUserId();

        try {
            List<?> result = stringRedisTemplate.execute(
                    LOGIN_FAILURE_SCRIPT,
                    List.of(
                            RedisContext.loginFailWindowKey(userId),
                            RedisContext.loginRiskCountKey(userId),
                            RedisContext.revokeBeforeKey(userId),
                            RedisContext.loginFreezeKey(userId)
                    ),
                    UUID.randomUUID().toString(),
                    String.valueOf(WINDOW_MILLIS),
                    String.valueOf(MAX_ATTEMPTS),
                    String.valueOf(RISK_COUNT_TTL_SECONDS),
                    FREEZE_STEPS_SECONDS,
                    String.valueOf(RedisContext.SESSION_REVOKE_TTL_SECONDS)
            );
            if (result.isEmpty() || toLong(result.get(0)) != 1L) {
                return;
            }

            long attempts = toLong(result.get(1));
            long freezeSeconds = toLong(result.get(2));
            long riskCount = toLong(result.get(3));
            log.warn("账号登录失败触发风控 userId={} username={} attempts={} riskCount={} freezeSeconds={}",
                    userId, userName, attempts, riskCount, freezeSeconds);
        } catch (Exception e) {
            log.error("登录失败风控脚本执行失败 userId={} username={}", userId, userName, e);
        }
    }

    /**
     * 与 {@code UserService.login} 保持同一优先级：先按用户名查，查不到再按邮箱查。
     * 异步线程不能依赖 UserContext，只能从事件里的账号回库取 userId。
     */
    private User findUserByAccount(String account) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("user_name", account));
        if (user == null) {
            user = userMapper.selectOne(new QueryWrapper<User>().eq("email", account));
        }
        return user;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long toLong(Object value) {
        return value instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(value));
    }
}
