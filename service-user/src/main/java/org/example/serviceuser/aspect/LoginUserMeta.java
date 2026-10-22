package org.example.serviceuser.aspect;

/**
 * 登录风控所需的账号元数据。
 *
 * <p>只保留 userId 与 status：失败链路只需要判断账号是否存在、是否已封禁/注销，
 * 邮箱等隐私字段不放进 Redis，邮件触发时再按 userId 回库取一次。</p>
 *
 * @param userId 用户 ID，{@link #NOT_FOUND} 时为 null
 * @param status 0-禁用 1-启用 2-已注销
 */
public record LoginUserMeta(Long userId, Integer status) {

    /** 账号不存在（负缓存命中）。 */
    public static final LoginUserMeta NOT_FOUND = new LoginUserMeta(null, null);

    public boolean exists() {
        return userId != null;
    }
}
