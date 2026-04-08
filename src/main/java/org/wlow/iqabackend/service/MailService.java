package org.wlow.iqabackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送服务.
 * 当前先提供纯文本验证码邮件能力.
 */
@Service
@RequiredArgsConstructor
public class MailService {
    private final JavaMailSender javaMailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    /**
     * 发送通用纯文本邮件.
     * 供集成测试或后续通知类场景复用.
     */
    public void sendPlainTextMail(String email, String subject, String content) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(content);
        javaMailSender.send(message);
    }

    /**
     * 发送密码重置验证码邮件.
     */
    public void sendPasswordResetCode(String email, String code) {
        sendPlainTextMail(email, "IQA系统密码重置验证码", """
                您正在进行密码重置操作。

                本次验证码为: %s

                验证码10分钟内有效，请勿泄露给他人。
                如果这不是您的操作，请忽略此邮件。
                """.formatted(code));
    }
}
