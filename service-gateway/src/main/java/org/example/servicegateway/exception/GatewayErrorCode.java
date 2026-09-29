package org.example.servicegateway.exception;

/**
 * 网关侧错误码（服务号 09，WebFlux 面）。
 *
 * <p>网关是响应式应用，不引入 servlet 侧的 {@code @RestControllerAdvice}，也不依赖
 * service-api/service-common，因此这里的文案与 service-api 的 CommonErrorCode
 * 保持一致（同码同文案）。修改通用文案时两处同步，避免网关与业务服务给出不同提示。</p>
 *
 * <p>只有网关自有语义才用服务号 09（如 {@code NOT_FOUND = 4040901}「接口不存在」，
 * 指路由未命中，与业务侧「内容不存在」4040001 不同）；通用语义一律沿用 00 段码，
 * 让调用方对同一含义只看到一个 code。</p>
 */
public enum GatewayErrorCode {

    PARAM_INVALID(4000001, "提交的内容不符合要求，请检查后重新提交"),
    LOGIN_REQUIRED(4010001, "登录状态已失效，请重新登录"),
    NO_PERMISSION(4030001, "没有权限执行该操作"),
    ADMIN_ONLY(4030002, "该功能仅限管理员使用"),
    NOT_FOUND(4040901, "接口不存在"),
    RATE_LIMITED(4290001, "操作过于频繁，请稍后再试"),
    INTERNAL_ERROR(5000001, "服务繁忙，请稍后重试"),
    DOWNSTREAM_UNAVAILABLE(5000003, "相关服务暂时无法响应，请稍后重试");

    private final int code;
    private final String message;

    GatewayErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
