package org.example.serviceuser.aspect;

import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.service.EmailService;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.UserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 登录失败风控核心逻辑：原异步 handler 的方法改为同步入参，由 {@link AccountProtection}
 * 直接调用，Lua 脚本保持原样复用。
 *
 * <p>已移除 {@code @AsyncHandler} 注解：登录入口现在走同步后置链，如果同时保留异步
 * 触发，同一次失败会被两条链路各记一次，5 次阈值实际 3 次就会触发。方法保留为普通
 * 公共方法，后续需要恢复异步链路时可直接重新注册。</p>
 *
 * <p>账号元数据通过 {@link LoginUserMetaCache} 读取，失败链路不再每次查库；只有缓存
 * 未命中或邮件真正发送时才访问数据库。</p>
 */
@Slf4j
@Component
public class AccountProtectionAsyncHandlers {

    /** 3 分钟滑动窗口。 */
    private static final long WINDOW_MILLIS = 3 * 60 * 1000L;
    /** 窗口内允许的最大失败次数，第 5 次触发风控。 */
    private static final int MAX_ATTEMPTS = 5;
    /** 风控次数保留 24 小时，每次触发续期，成功登录立即删除。 */
    private static final long RISK_COUNT_TTL_SECONDS = 24 * 60 * 60L;
    /** 冻结档位：30s / 1m / 5m / 10m / 30m / 1h / 6h / 12h / 1d，之后恒为 1d。 */
    private static final String FREEZE_STEPS_SECONDS = "30,60,300,600,1800,3600,21600,43200,86400";

    /** 风控邮件节流：同一用户 1 小时内最多一封，避免攻击者反复触发造成邮件轰炸。 */
    private static final Duration MAIL_THROTTLE_TTL = Duration.ofHours(1);

    private static final String MAIL_SUBJECT = "CodeWise 登录安全提醒";
    private static final DateTimeFormatter MAIL_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
    private final LoginUserMetaCache loginUserMetaCache;
    private final UserMapper userMapper;
    private final EmailService emailService;

    public AccountProtectionAsyncHandlers(
            StringRedisTemplate stringRedisTemplate,
            LoginUserMetaCache loginUserMetaCache,
            UserMapper userMapper,
            EmailService emailService
    ) {
        // Lua 里的失效线需要 tonumber 解析；RedisTemplate<String,Object> 的 Jackson
        // 值序列化器会把数字写成带引号的字符串，这里必须使用 StringRedisTemplate。
        this.stringRedisTemplate = stringRedisTemplate;
        this.loginUserMetaCache = loginUserMetaCache;
        this.userMapper = userMapper;
        this.emailService = emailService;
    }

    /**
     * 登录成功：清空该账号的失败窗口、风控次数和冻结 key。
     *
     * @param userId   用户 ID，为空说明结果里没有用户信息，直接跳过
     * @param userName 用户名，仅用于日志
     */
    public void loginSuccess(Long userId, String userName) {
        if (userId == null) {
            log.info("用户Id为空，用户名 {}", userName);
            return;
        }
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

    /**
     * 登录失败：滑动窗口计数，达到阈值时写失效线、冻结 key 并发送提醒邮件。
     *
     * @param account 登录账号（用户名或邮箱）
     */
    public void loginFailure(String account) {
        if (account == null || account.isBlank()) {
            log.warn("登录失败事件缺少账号，跳过风控");
            return;
        }

        LoginUserMeta meta;
        try {
            meta = loginUserMetaCache.get(account);
        } catch (Exception e) {
            // 异步/后置风控失败不应影响登录响应；数据库异常只记日志，交由后续请求重试累计。
            log.error("登录失败风控查询用户异常 account={}", account, e);
            return;
        }
        if (meta == null || !meta.exists()) {
            // 账号不存在时没有 userId，无法写用户级失效线；不存在账号的爆破交给 IP 维度限流。
            return;
        }
        // 已封禁 / 已注销账号不再参与登录风控，避免覆盖管理员写入的失效线。
        if (meta.status() != null && (meta.status() == 0 || meta.status() == 2)) {
            return;
        }
        Long userId = meta.userId();

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
            log.warn("账号登录失败触发风控 userId={} account={} attempts={} riskCount={} freezeSeconds={}",
                    userId, account, attempts, riskCount, freezeSeconds);
            sendRiskMailQuietly(userId, freezeSeconds, riskCount);
        } catch (Exception e) {
            log.error("登录失败风控脚本执行失败 userId={} account={}", userId, account, e);
        }
    }

    /**
     * 发送风控提醒邮件：先抢占小时级节流 key，抢到才回库取邮箱并发送。
     *
     * <p>节流放在回库之前，保证即使高频触发也只有一次数据库查询；发送失败会释放
     * 节流 key，让下一次触发可以重试。</p>
     */
    private void sendRiskMailQuietly(Long userId, long freezeSeconds, long riskCount) {
        String throttleKey = RedisContext.loginRiskMailKey(userId);
        boolean acquired = false;
        try {
            acquired = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(throttleKey, "1", MAIL_THROTTLE_TTL));
            if (!acquired) {
                return;
            }

            User user = userMapper.selectById(userId);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                // 没有可投递邮箱，释放节流，避免用户后续补绑邮箱仍被挡住。
                stringRedisTemplate.delete(throttleKey);
                return;
            }

            emailService.sendEmail(user.getEmail(), MAIL_SUBJECT,
                    buildRiskMailHtml(user.getUserName(), freezeSeconds, riskCount));
        } catch (Exception e) {
            if (acquired) {
                releaseThrottleQuietly(throttleKey);
            }
            // 邮件只是提醒，发送失败绝不能影响登录响应或风控结果。
            log.error("发送登录风控提醒邮件失败 userId={}", userId, e);
        }
    }

    private void releaseThrottleQuietly(String throttleKey) {
        try {
            stringRedisTemplate.delete(throttleKey);
        } catch (Exception e) {
            log.warn("释放登录风控邮件节流 key 失败 key={}", throttleKey, e);
        }
    }

    /**
     * 构造 HTML 邮件正文。
     *
     * <p>必须以 {@code <html} 开头，service-message 用这个前缀判定 HTML 邮件；
     * 正文不能出现「验证码」三个字，否则会被验证码模板误判。</p>
     */
    static String buildRiskMailHtml(String userName, long freezeSeconds, long riskCount) {
        String displayName = userName == null || userName.isBlank() ? "你的账号" : escapeHtml(userName);
        String freezeUntil = LocalDateTime.now().plusSeconds(freezeSeconds)
                .format(MAIL_TIME_FORMATTER);
        return "<html><body style=\"margin:0;padding:24px;background:#f6f8fb;font-family:Arial,'Microsoft YaHei',sans-serif;\">"
                + "<div style=\"max-width:560px;margin:0 auto;background:#ffffff;border-radius:18px;padding:34px;"
                + "border:1px solid #eef2f7;box-shadow:0 10px 30px rgba(15,23,42,0.08);\">"
                + "<div style=\"font-size:22px;font-weight:700;color:#111827;margin-bottom:10px;\">CodeWise 登录安全提醒</div>"
                + "<div style=\"font-size:14px;color:#6b7280;line-height:1.8;margin-bottom:20px;\">"
                + "账号 <b style=\"color:#111827;\">" + displayName + "</b> 在 3 分钟内连续 "
                + MAX_ATTEMPTS + " 次密码错误，系统已临时冻结登录。</div>"
                + "<div style=\"margin:22px 0;padding:18px 20px;background:linear-gradient(135deg,#eff6ff,#f8fafc);"
                + "border:1px solid #dbeafe;border-radius:14px;font-size:14px;color:#374151;line-height:1.9;\">"
                + "冻结至：<b>" + freezeUntil + "</b><br>"
                + "本次冻结时长：<b>" + humanDuration(freezeSeconds) + "</b><br>"
                + "累计触发次数：<b>第 " + riskCount + " 次</b></div>"
                + "<div style=\"font-size:14px;color:#374151;line-height:1.8;\">"
                + "如果这不是你本人操作，说明有人正在尝试登录你的账号，建议立即修改密码，"
                + "并检查账号是否有异常登录记录。</div>"
                + "<div style=\"margin-top:22px;padding:14px 16px;background:#fffbeb;border:1px solid #fde68a;"
                + "border-radius:12px;color:#92400e;font-size:13px;line-height:1.7;\">"
                + "系统不会向你索要密码，也不会要求你点击任何链接解除冻结。冻结到期后会自动恢复。</div>"
                + "<div style=\"margin-top:28px;border-top:1px solid #e5e7eb;padding-top:18px;color:#9ca3af;"
                + "font-size:12px;text-align:center;\">CodeWise Online Judge - 本邮件由系统自动发送，请勿回复</div>"
                + "</div></body></html>";
    }

    private static String humanDuration(long seconds) {
        if (seconds >= 86400) {
            return (seconds / 86400) + " 天";
        }
        if (seconds >= 3600) {
            return (seconds / 3600) + " 小时";
        }
        if (seconds >= 60) {
            return (seconds / 60) + " 分钟";
        }
        return Math.max(1, seconds) + " 秒";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private long toLong(Object value) {
        return value instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(value));
    }
}
