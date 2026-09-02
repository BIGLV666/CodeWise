package org.example.serviceuser.service;

import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import org.example.servicecommon.service.EmailService;
import org.example.serviceuser.entry.User;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户邮件内容构建与发送。
 * <p>
 * 将邮件正文（验证码、登录提醒、封禁/冻结/解封通知）的模板加载、占位符替换、
 * HTML 转义等职责从 {@link UserService} 中剥离，统一由本类负责。
 * 真正的邮件投递仍然委托给 {@link EmailService}（走 RabbitMQ）。
 */
@Service
public class UserMailService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EmailService emailService;

    public UserMailService(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * 发送验证码邮件（纯文本，保证投递率）。
     *
     * @param email      收件人邮箱
     * @param code       验证码
     * @param ttlMinutes 验证码有效期（分钟），须与 Redis 缓存时长保持一致
     */
    public void sendCodeMail(String email, String code, long ttlMinutes) {
        String content = String.format(
                "【CodeWise】验证码:%s 用于邮箱身份验证，%d分钟内有效，请勿泄露和转发。如非本人操作，请忽略此邮件。",
                code, ttlMinutes);
        emailService.sendEmail(email, "CodeWise 安全验证", content);
    }

    /**
     * 发送登录提醒邮件。
     */
    public void sendLoginNotification(AsyncEvent event, User user) {
        String html = buildLoginNotification(event, user);
        emailService.sendEmail(user.getEmail(), "登录提醒", html);
    }

    /**
     * 发送封禁通知。
     */
    public void sendBanMail(User user) {
        String html = render("templates/ban-email.html",
                "{{username}}", user.getUserName(),
                "{{banUntil}}", format(user.getBanTime()));
        emailService.sendEmail(user.getEmail(), "CodeWise 封禁通知", html);
    }

    /**
     * 发送冻结通知。
     */
    public void sendFreezeMail(User user) {
        String html = render("templates/freeze-email.html",
                "{{username}}", user.getUserName(),
                "{{banReason}}", user.getBanReason());
        emailService.sendEmail(user.getEmail(), "CodeWise 冻结通知", html);
    }

    /**
     * 发送解封通知。
     */
    public void sendUnbanMail(User user) {
        String html = render("templates/unban-email.html",
                "{{username}}", user.getUserName());
        emailService.sendEmail(user.getEmail(), "CodeWise 解封通知", html);
    }

    private String buildLoginNotification(AsyncEvent event, User user) {
        String template = loadTemplate("templates/login-notification-email.html");
        String userAgent = stringData(event, "userAgent", "未知设备");
        return template
                .replace("{{username}}", escapeHtml(stringData(
                        event, "username", user.getUserName())))
                .replace("{{loginTime}}", escapeHtml(stringData(
                        event, "loginTime", LocalDateTime.now().toString())))
                .replace("{{location}}", "未知")
                .replace("{{ipAddress}}", escapeHtml(stringData(
                        event, "ipAddress", "未知")))
                .replace("{{device}}", escapeHtml(userAgent))
                .replace("{{browser}}", escapeHtml(userAgent))
                .replace("{{securityCenterUrl}}", "#")
                .replace("{{changePasswordUrl}}", "#")
                .replace("{{supportUrl}}", "#")
                .replace("{{privacyPolicyUrl}}", "#")
                .replace("{{termsUrl}}", "#")
                .replace("{{unsubscribeUrl}}", "#");
    }

    /** 加载模板并依次替换成对出现的「占位符,值」。 */
    private String render(String templatePath, String... placeholders) {
        String template = loadTemplate(templatePath);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            template = template.replace(placeholders[i], escapeHtml(placeholders[i + 1]));
        }
        return template;
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load email template: " + path, ex);
        }
    }

    private String stringData(AsyncEvent event, String key, String defaultValue) {
        Object value = event.data().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String format(LocalDateTime time) {
        return time == null ? "未知" : time.format(DATE_TIME_FORMATTER);
    }
}
