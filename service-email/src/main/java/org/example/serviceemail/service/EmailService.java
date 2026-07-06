package org.example.serviceemail.service;

import com.rabbitmq.client.Channel;
import jakarta.mail.internet.MimeMessage;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.EmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    Logger log = LoggerFactory.getLogger(this.getClass());
    @Autowired
    private JavaMailSender mailSender;
    @RabbitListener(queues = MqContexts.EMAIL_QUEUE)
    public void sendEmail(EmailMessage message, Channel channel, Message amqpMessage) {
        try {
            log.info("收到邮件任务，收件人: {}", message.getTo());

            // 发邮件
            MimeMessage mail = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mail, true, "UTF-8");
            helper.setFrom("379299583@qq.com");  // 必须和授权码邮箱一致
            helper.setTo(message.getTo());
            helper.setSubject(message.getSubject());
            if (isCodeEmail(message.getContent())) {
                helper.setText(buildHtmlContent(message.getContent()), true);
            } else {
                helper.setText(message.getContent(), false);
            }
            mailSender.send(mail);

            // 成功 → 确认消息
            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
            log.info("✅ 邮件发送成功: {}", message.getTo());

        } catch (Exception e) {
            log.error("❌ 邮件发送失败: {}, 错误: {}", message.getTo(), e.getMessage());

            // 失败 → 也确认消息（避免无限重试）
            try {
                channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
            } catch (Exception ackEx) {
                log.error("确认消息失败: {}", ackEx.getMessage());
            }
        }
    }

    private boolean isCodeEmail(String content) {
        return content != null && content.contains("验证码");
    }

    private String buildHtmlContent(String content) {
        String safeContent = content == null ? "" : content;
        String code = extractCode(safeContent);
        return "<div style=\"max-width:560px;margin:0 auto;padding:32px;background:#f6f8fb;font-family:Arial,'Microsoft YaHei',sans-serif;\">"
                + "<div style=\"background:#ffffff;border-radius:18px;padding:34px;box-shadow:0 10px 30px rgba(15,23,42,0.08);border:1px solid #eef2f7;\">"
                + "<div style=\"font-size:22px;font-weight:700;color:#111827;margin-bottom:10px;\">CodeWise 安全验证</div>"
                + "<div style=\"font-size:14px;color:#6b7280;line-height:1.8;margin-bottom:24px;\">你好，你正在进行邮箱身份验证。请使用下方验证码完成操作。</div>"
                + "<div style=\"margin:24px 0;padding:20px 0;text-align:center;background:linear-gradient(135deg,#eff6ff,#f8fafc);border-radius:14px;border:1px solid #dbeafe;\">"
                + "<span style=\"font-size:34px;font-weight:800;letter-spacing:8px;color:#2563eb;\">" + code + "</span>"
                + "</div>"
                + "<div style=\"font-size:14px;color:#374151;line-height:1.8;\">验证码有效期为 <b>5 分钟</b>。请勿将验证码泄露给他人。</div>"
                + "<div style=\"margin-top:22px;padding:14px 16px;background:#fffbeb;border:1px solid #fde68a;border-radius:12px;color:#92400e;font-size:13px;line-height:1.7;\">如果这不是你本人操作，请忽略此邮件。系统不会向你索要验证码。</div>"
                + "<div style=\"margin-top:28px;border-top:1px solid #e5e7eb;padding-top:18px;color:#9ca3af;font-size:12px;text-align:center;\">© CodeWise Online Judge · 本邮件由系统自动发送，请勿回复</div>"
                + "</div>"
                + "</div>";
    }

    private String extractCode(String content) {
        String code = content.replaceAll(".*验证码[:：]\\s*([0-9A-Za-z]{4,8}).*", "$1");
        return code.equals(content) ? content : code;
    }

}
