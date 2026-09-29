package org.example.serviceapi.exception;

/**
 * 业务异常：消息一律来自 {@link ErrorCode} 枚举，调用点不写死文案。
 *
 * <p>只有本异常的 message 会被原样返回给前端；其他异常由全局处理器换成通用文案，
 * 因此不要用 BizException 携带 SQL、堆栈或下游服务的原始错误。</p>
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 带占位符的文案，如枚举消息为 "标题最多 %s 个字符"。 */
    public BizException(ErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.errorCode = errorCode;
    }

    /** cause 只进服务端日志，不会出现在返回给前端的消息里。 */
    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(String.format(errorCode.getMessage(), args), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }
}
