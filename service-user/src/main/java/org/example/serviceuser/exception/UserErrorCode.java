package org.example.serviceuser.exception;

import org.example.serviceapi.exception.ErrorCode;

/**
 * service-user 错误码（服务号 01）：编码规则见
 * {@link org.example.serviceapi.exception.CommonErrorCode}。
 *
 * <p>消息文案面向用户，禁止写入 SQL、下游原始报错或异常类名；需要携带原因时用
 * {@code new BizException(枚举, cause)}，cause 只进日志。</p>
 *
 * <p>跨服务语义（未登录、无权限、参数不符、内容不存在兜底、保存失败、状态冲突、
 * 系统繁忙）一律复用 {@code CommonErrorCode}，本枚举只收录账号、登录注册、验证码、
 * 申诉与头像上传等本服务专属语义。带 {@code %s} 占位的消息通过
 * {@code new BizException(枚举, args...)} 填充。</p>
 */
public enum UserErrorCode implements ErrorCode {

    // ---------- 登录 / 注册 / 找回密码 ----------
    LOGIN_FAILED(4010101, "账号或密码不正确，请核对后重试"),

    CODE_EXPIRED(4000101, "验证码已过期，请重新获取"),
    CODE_INCORRECT(4000102, "验证码不正确，请核对后重新输入"),
    CODE_TOO_MANY_ATTEMPTS(4000103, "验证码错误次数过多，本次验证已作废，请重新获取"),
    USERNAME_OR_PASSWORD_MISSING(4000104, "请填写用户名和密码后再登录"),
    EMAIL_OR_PASSWORD_MISSING(4000105, "请填写邮箱和密码后再登录"),
    EMAIL_MISSING(4000106, "请填写邮箱地址"),
    PASSWORD_MISSING(4000107, "请输入密码"),
    PASSWORD_TOO_SHORT(4000108, "密码长度不能少于 %s 位，请调整后重新提交"),
    PASSWORD_INCORRECT(4000109, "原密码不正确，请核对后重试"),
    USERNAME_TAKEN(4090101, "该用户名已被使用，请换一个试试"),
    EMAIL_TAKEN(4090102, "该邮箱已被注册，请直接登录或找回密码"),
    REGISTER_ACCOUNT_TAKEN(4090103, "该邮箱或手机号已被注册，请直接登录"),

    // ---------- 账号状态 ----------
    ACCOUNT_BANNED(4030101, "当前账号处于限制登录状态，%s。如有异议可提交申诉"),
    USER_CANCELLED(4090107, "该账号已注销，不支持此操作"),
    USER_NOT_FROZEN(4090108, "该账号当前未被冻结，无需解冻"),
    FREEZE_REASON_MISSING(4000117, "请填写冻结原因"),

    // ---------- 用户资料 ----------
    BIO_EMPTY(4000118, "请填写个人简介"),
    BIO_TOO_LONG(4000119, "个人简介不能超过 %s 个字符，请调整后重新提交"),
    BIRTHDAY_MISSING(4000120, "请选择出生日期"),
    BIRTHDAY_FUTURE(4000121, "出生日期不能晚于今天，请重新选择"),
    BIRTHDAY_FORMAT_INVALID(4000122, "出生日期格式不正确，请按 2000-01-01 的格式填写"),
    NICKNAME_MISSING(4000123, "请填写昵称"),
    NICKNAME_TOO_LONG(4000124, "昵称不能超过 %s 个字符，请调整后重新提交"),
    AVATAR_MISSING(4000125, "请先选择要使用的头像"),

    // ---------- 用户 / 申诉 不存在 ----------
    USER_NOT_FOUND(4040101, "用户不存在或已被删除"),
    APPEAL_NOT_FOUND(4040102, "申诉记录不存在或已被删除"),

    // ---------- 申诉 ----------
    APPEAL_INFO_MISSING(4000116, "请填写手机号或邮箱，并写明申诉理由后再提交"),
    APPEAL_NOT_BANNED(4090104, "当前账号未被封禁，无需提交申诉"),
    APPEAL_ALREADY_PROCESSED(4090105, "该申诉已处理完成，请勿重复处理"),
    APPEAL_DUPLICATE(4090106, "您已提交过申诉，请耐心等待处理结果"),

    // ---------- 文件上传 ----------
    CODE_SEND_TOO_FREQUENT(4290101, "验证码发送过于频繁，请 1 分钟后再试"),
    FILE_EMPTY(4000110, "请先选择要上传的文件"),
    UPLOAD_TARGET_INVALID(4000111, "上传方式不正确，请返回页面重新操作"),
    FILE_NOT_IMAGE(4000112, "仅支持上传图片文件，请选择图片后重试"),
    IMAGE_FORMAT_UNSUPPORTED(4000113, "图片仅支持 jpg、jpeg、png、gif 格式，请更换图片后重试"),
    IMAGE_CONTENT_INVALID(4000114, "该文件无法识别为有效图片，请重新选择后上传"),
    IMAGE_SIZE_EXCEEDED(4000115, "图片大小不能超过 %s MB，请选择更小的图片"),
    IMAGE_CHECK_FAILED(5000101, "图片文件校验未通过，请稍后重新上传"),
    FILE_SAVE_FAILED(5000102, "文件保存失败，请稍后重新上传"),
    UPLOAD_PATH_CONFIG_ERROR(5000103, "文件保存位置配置有误，请联系管理员处理"),

    // ---------- 邮件 ----------
    MAIL_TEMPLATE_UNAVAILABLE(5000104, "邮件内容暂时无法生成，请稍后重试或联系管理员");

    private final int code;
    private final String message;

    UserErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
