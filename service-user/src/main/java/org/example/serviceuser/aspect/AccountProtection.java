package org.example.serviceuser.aspect;

import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PostFilter;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.exception.BizException;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.exception.UserErrorCode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * 登录风控同步后置链：成功清空失败记录，失败累计滑动窗口。
 *
 * <p>直接复用 {@link AccountProtectionAsyncHandlers} 里原有的 Lua 脚本，不重复实现计数逻辑。
 * 只处理登录接口；验证码登录不属于密码爆破场景，不参与计数。</p>
 */
@Component
@Order(501)
@Slf4j
public class AccountProtection implements PostFilter {

    /** 用户名/密码、邮箱/密码两个入口共用同一份账号风控，避免冻结后切换入口绕过。 */
    static final Set<String> LOGIN_METHODS = Set.of("login", "emailLogin");

    private final AccountProtectionAsyncHandlers accountProtectionHandlers;
    private final LoginUserMetaCache loginUserMetaCache;

    public AccountProtection(AccountProtectionAsyncHandlers accountProtectionHandlers,
                             LoginUserMetaCache loginUserMetaCache) {
        this.accountProtectionHandlers = accountProtectionHandlers;
        this.loginUserMetaCache = loginUserMetaCache;
    }

    @Override
    public void doFilter(FilterContext context) {
        if (context == null) {
            log.error("后置链业务元数据为空，类名 AccountProtection");
            return;
        }
        Method method = context.getMethod();
        if (method == null) {
            log.error("用户风控后置链方法名为空，类名 AccountProtection");
            return;
        }
        if (!LOGIN_METHODS.contains(method.getName())) {
            return;
        }

        if (context.getError() != null) {
            if (isLoginFailure(context.getError())) {
                String account = firstStringArgument(context.getArgs());
                if (account != null) {
                    accountProtectionHandlers.loginFailure(account);
                }
            }
            return;
        }

        UserDto user = parseLoginUser(context.getResult());
        if (user == null) {
            return;
        }
        try {
            Long userId = Long.valueOf(user.getUserId());
            // 登录成功后刷新元数据，保证后续失败链路直接命中缓存。
            loginUserMetaCache.put(userId, user.getStatus(), user.getUserName(), user.getEmail());
            accountProtectionHandlers.loginSuccess(userId, user.getUserName());
        } catch (NumberFormatException e) {
            log.warn("登录成功结果中的 userId 格式异常，跳过热控清理 userId={}", user.getUserId());
        }
    }

    @Override
    public int getOrder() {
        return 501;
    }

    /**
     * 只有「账号或密码不正确」才计入风控；验证码错误、参数缺失、前置冻结拒绝等都不算。
     */
    private boolean isLoginFailure(Throwable error) {
        if (error instanceof BizException bizException) {
            return bizException.getCode() == UserErrorCode.LOGIN_FAILED.getCode();
        }
        return false;
    }

    /**
     * Controller 返回的是 {@code Result<Map<String,Object>>}，用户信息在 {@code data.user}；
     * 直接对返回值做 Map 判断永远拿不到，必须先拆 Result。
     */
    private UserDto parseLoginUser(Object result) {
        if (!(result instanceof Result<?> wrapper)
                || !(wrapper.getData() instanceof Map<?, ?> data)
                || !(data.get("user") instanceof UserDto user)) {
            return null;
        }
        return user;
    }

    private String firstStringArgument(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return null;
        }
        String account = String.valueOf(args[0]);
        return account.isBlank() ? null : account;
    }
}
