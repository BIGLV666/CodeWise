package org.example.servicecommon.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 下游服务统一身份拦截器：校验网关注入的 {@code X-Internal-Token}，
 * 并把 {@code X-User-Id}、{@code X-User-Name}、{@code X-Real-IP} 写入 {@link UserContext}。
 *
 * <p>内部 Token 一律来自 {@code codewise.internal-token} 配置（环境变量
 * {@code CODEWISE_INTERNAL_TOKEN}），无默认值——缺失时启动失败即暴露配置遗漏。
 * 请求头细节不落日志（历史版本曾全量打印请求头，含 Authorization 与内部 Token）。</p>
 *
 * <p>除身份透传外，还负责「凭证失效」判定（登出、改密、管理员封禁共用）：
 * 见 {@link #isSessionRevoked(String, String, String)} —— token 桶直接拒绝；
 * 用户级失效线与网关注入的 JWT {@code iat} 比较，旧凭证一律拒绝。</p>
 */
@AutoConfiguration
@Slf4j
public class UserAuthInterceptor implements HandlerInterceptor {
    /** 管理员角色标识 */
    private static final Integer ADMIN_ROLE = 2;
    /** 内部通信密钥：与网关配置保持一致，无默认值（缺失即启动失败） */
    @Value("${codewise.internal-token}")
    private String internalToken;
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/user/login",
            "/api/user/emaillogin",
            "/api/user/emailloginforcode",
            "/api/user/getemailcode",
            "/api/user/emailregister",
            "/api/user/register",
            "/api/user/updatepasswordforemail",
            "/api/user/updatefromcode",
            "/api/user/appeal/submit"
    );
    private static final Set<String> PUBLIC_PATH_PATTERNS = Set.of(
            "/uploads/**",
            "/websocket/**",
            "/ws/**",
            "/sockjs/**"
    );
    /** 存活探针路径：容器健康检查无内部 Token，必须在 Token 校验前放行（仅暴露 UP/DOWN 状态） */
    private static final Set<String> PROBE_PATHS = Set.of("/actuator/health", "/actuator/health/", "/actuator/info");

    /**
     * 凭证失效校验脚本（一次 EVAL 原子完成全部判定）。
     *
     * <pre>
     * KEYS[1] = token 桶（logout_token:&lt;token&gt;）
     * KEYS[2] = 用户级失效线（revoke_before:&lt;userId&gt;，可能不传：请求没带 X-User-Id 时）
     * ARGV[1] = token 原文
     * ARGV[2] = 兜底落库 TTL（秒）
     * ARGV[3] = 本凭证的签发时间（epoch 秒，网关注入的 JWT iat；缺失传空串）
     * 返回 1 = 放行；0 = 拒绝
     * </pre>
     *
     * <p>判定顺序：① token 桶命中 → 拒；② 该用户有失效线且「本凭证无法证明比失效线新
     * （iat 缺失或 iat ≤ 失效线）」→ 把 token 兜底写进 token 桶后拒；③ 其余放行。</p>
     *
     * <p><b>失效线只读不删</b>（脚本内没有 DEL KEYS[2]）：删掉会让封禁期间没使用过的旧 token
     * 在解封后复活。解封不需要任何清理动作——新登录的 token {@code iat} 更晚，自然通过。</p>
     *
     * <p>iat 缺失时按「拒」处理（fail-closed）：只在用户确实存在失效线时才走到这条分支，
     * 正常流量经网关一定带 iat；若网关未升级可能出现短暂误拒，属于可观测、可回滚的取舍。</p>
     */
    static final String SESSION_REVOKE_SCRIPT_TEXT =
            "if redis.call('EXISTS', KEYS[1]) == 1 then\n"
                    + "  return 0\n"
                    + "end\n"
                    + "if #KEYS >= 2 then\n"
                    + "  local revokeBefore = redis.call('GET', KEYS[2])\n"
                    + "  if revokeBefore then\n"
                    + "    local iat = tonumber(ARGV[3])\n"
                    + "    if iat == nil or iat <= tonumber(revokeBefore) then\n"
                    + "      redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))\n"
                    + "      return 0\n"
                    + "    end\n"
                    + "  end\n"
                    + "end\n"
                    + "return 1";

    private static final RedisScript<Long> SESSION_REVOKE_SCRIPT =
            new DefaultRedisScript<>(SESSION_REVOKE_SCRIPT_TEXT, Long.class);

    /** 网关注入的凭证签发时间头（epoch 秒，取自 JWT {@code iat}）。 */
    private static final String TOKEN_IAT_HEADER = "X-User-Token-Iat";

    AntPathMatcher antPathMatcher = new AntPathMatcher();
    @Lazy
    @Autowired(required = false)
    private UserFeignClient userFeignClient;

    /**
     * 用 {@link StringRedisTemplate} 执行脚本：它与 {@code RedisTemplate<String,Object>}
     * 的 key 序列化器同为 {@code StringRedisSerializer}，key 字节完全一致；
     * 直接用后者执行脚本会让 ARGV 走 Jackson 序列化，与 {@code UserService} 写入的形态对不上。
     */
    @Lazy
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)  {
        String path = request.getRequestURI();
        String ip = request.getHeader("X-Real-IP");
        if (ip != null) {
            UserContext.setCurrentIp(ip);
        }
        // 存活探针在内部 Token 校验之前放行，供容器编排 healthcheck 使用。
        if (PROBE_PATHS.contains(path)) {
            return true;
        }
        // 验证内部 Token 后，才允许进入任何匿名白名单。
        String headerToken = request.getHeader("X-Internal-Token");
        if (headerToken == null || !headerToken.equals(internalToken)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            try {
                response.getWriter().write("{\"code\":403,\"message\":\"禁止直接访问，请通过网关访问\"}");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return false;
        }


        String normalizedPath = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        if (PUBLIC_PATHS.contains(normalizedPath)
                || PUBLIC_PATH_PATTERNS.stream().anyMatch(pattern -> antPathMatcher.match(pattern, normalizedPath))) {
            return true;
        }

        // WebSocket 握手的 JWT 校验由网关和 WebSocket 握手拦截器共同完成。
        String upgrade = request.getHeader("Upgrade");
        if ("websocket".equalsIgnoreCase(upgrade)) {
            return true;
        }

        // 从请求头获取 userId（网关塞的）；解析不出来视为「无身份」，
        // 不能让一个脏头把整个请求变成 500（下面按用户维度校验封禁也要用到它）
        String userIdStr = request.getHeader("X-User-Id");
        Long userId = parseUserId(userIdStr);
        if (userId != null) {
            UserContext.setUserId(userId);
        }

        // 4. 获取用户名（如果有）
        String userName = request.getHeader("X-User-Name");
        if (userName != null) {
            UserContext.setUserName(userName);
        }
        // 获取用户jwtToken（网关注入；客户端伪造值已由网关剥离）
        String userToken=request.getHeader("X-User-Token");
        if (userToken != null && !userToken.isBlank()) {
            UserContext.setUserToken(userToken);
        }

        // 判断token不为空时在此调用redis，不把io操作放网关上，网关做初步正确性校验后在此判断凭证是否已失效
        // 无 token（如带内部 Token 的服务间调用）跳过查询，避免用 "logout_token:null" 空转一次 io
        if (userToken != null && !userToken.isBlank()) {
            if (isSessionRevoked(userToken, userIdStr, request.getHeader(TOKEN_IAT_HEADER))) {
                // HTTP 保持 200，前端按响应体 code 分支（与全局异常处理器口径一致）；
                // 中文文案必须显式声明 UTF-8，否则 getWriter 按容器默认编码输出乱码
                try {
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":403,\"message\":\"您的登录已过期，请重新登录\"}");
                } catch (IOException e) {
                    // 与 Redis 故障区分：这里已经确认凭证失效，写响应失败也必须拒绝，
                    // 不能因为客户端连接异常就放行业务逻辑
                    log.error("凭证失效响应写入失败{}", e.getMessage(), e);
                }
                return false;
            }
        }


        if(antPathMatcher.match(path,"/api/*/governance")){
            try{
            if(userId == null){
                response.getWriter().write("{\"code\":401,\"message\":\"请登录后重试\"}");
                return false;
            }
            Result<UserDto>body=userFeignClient.getUserInfo(userId);
            if(body==null||body.getData()==null){
                response.getWriter().write("{\"code\":401,\"message\":\"请登录后重试\"}");
                return false;
            }
            UserDto userDto=body.getData();
            if(userDto.getRoleId()!=ADMIN_ROLE){
                response.getWriter().write("{\"code\":403,\"message\":\"无权访问\"}");
                return false;
            }
            } catch (IOException e) {
                log.error("权限校验失败{}",e.getMessage(),e);
            }
        }


        return true;
}

    /**
     * 当前请求携带的凭证是否已失效：登出（token 桶）与「用户级失效线」（封禁／改密）共用一次判定。
     *
     * <p>失效线 = {@code revoke_before:<userId>}（epoch 秒），由 {@code UserService} 在封禁／改密时写入；
     * 这里把网关注入的 JWT {@code iat}（{@code X-User-Token-Iat}）与它比较，
     * <b>iat ≤ 失效线即视为已失效</b>——所以封禁／改密会一次性覆盖该用户所有已签发凭证（含多端），
     * 而重新登录签发的新 token {@code iat} 更晚，天然可用，不需要任何「解封清理」动作。</p>
     *
     * <p>为什么不在共享拦截器里直接解析 JWT：{@code jwt.secret} 只有 user/message/review/gateway
     * 四处配置，question/community/ai 没有。改用网关解析后透传 {@code iat}，既避免把密钥铺到所有服务，
     * 也避免每个请求都做一次签名校验。</p>
     *
     * <p>Redis 不可用或脚本执行失败时<b>放行</b>（与既有失败放行口径一致），只落日志。</p>
     *
     * @param userToken 网关注入的原始 token
     * @param userIdStr 网关注入的 {@code X-User-Id}，缺失或非法时只查 token 桶
     * @param tokenIat  网关注入的凭证签发时间（epoch 秒），缺失传空串（脚本按拒处理）
     * @return true＝已失效需要拒绝
     */
    private boolean isSessionRevoked(String userToken, String userIdStr, String tokenIat) {
        if (stringRedisTemplate == null) {
            // 无 Redis（未引入 redis 依赖的宿主）：跳过校验，保持与既有「失败放行」一致
            return false;
        }
        try {
            List<String> keys = revokeCheckKeys(userToken, userIdStr);
            Long result = stringRedisTemplate.execute(SESSION_REVOKE_SCRIPT, keys,
                    userToken,
                    String.valueOf(RedisContext.SESSION_REVOKE_TTL_SECONDS),
                    tokenIat == null ? "" : tokenIat);
            return result != null && result == 0L;
        } catch (Exception e) {
            log.error("凭证失效校验失败{}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 组装脚本的 KEYS：始终带 token 桶；只有拿到可解析的 userId 时才带失效线
     * （网关正常转发时两者都有，服务间调用只有内部 Token、两者都没有）。
     */
    private List<String> revokeCheckKeys(String userToken, String userIdStr) {
        String tokenKey = RedisContext.logoutTokenKey(userToken);
        Long userId = parseUserId(userIdStr);
        return userId == null ? List.of(tokenKey) : List.of(tokenKey, RedisContext.revokeBeforeKey(userId));
    }

    /** 容忍非法/缺失的 userId 头：解析不出来就退化为「只查 token 桶」，不影响请求处理。 */
    private Long parseUserId(String userIdStr) {
        if (userIdStr == null || userIdStr.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            log.warn("X-User-Id 非法，已跳过用户级封禁校验: {}", userIdStr);
            return null;
        }
    }
@Override
public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
}
}
