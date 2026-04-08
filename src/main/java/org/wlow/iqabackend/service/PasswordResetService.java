package org.wlow.iqabackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.wlow.iqabackend.data.entity.PasswordResetCode;
import org.wlow.iqabackend.data.entity.User;
import org.wlow.iqabackend.mapper.PasswordResetCodeMapper;
import org.wlow.iqabackend.mapper.UserMapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 找回密码服务.
 * 使用邮箱验证码实现发码和重置密码流程.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {
    private final UserMapper userMapper;
    private final PasswordResetCodeMapper passwordResetCodeMapper;
    private final MailService mailService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${password-reset.code-expiration-seconds}")
    private long codeExpirationSeconds;

    @Value("${password-reset.send-cooldown-seconds}")
    private long sendCooldownSeconds;

    @Value("${password-reset.max-attempts}")
    private int maxAttempts;

    /**
     * 发送密码重置验证码.
     * 为避免邮箱枚举, 即使邮箱不存在也不会抛出用户不存在错误.
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendResetCode(String email) {
        String normalizedEmail = normalizeEmail(email);
        User user = findByEmail(normalizedEmail);
        if (user == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        PasswordResetCode latest = passwordResetCodeMapper.selectLatestByEmail(normalizedEmail);
        if (latest != null
                && latest.getCreatedAt() != null
                && latest.getCreatedAt().plusSeconds(sendCooldownSeconds).isAfter(now)) {
            return;
        }

        passwordResetCodeMapper.consumeActiveCodesByEmail(normalizedEmail, now);

        String code = generateCode();
        PasswordResetCode resetCode = new PasswordResetCode();
        resetCode.setUserId(user.getId());
        resetCode.setEmail(normalizedEmail);
        resetCode.setCodeHash(passwordEncoder.encode(code));
        resetCode.setExpiresAt(now.plusSeconds(codeExpirationSeconds));
        resetCode.setAttemptCount(0);
        resetCode.setCreatedAt(now);
        passwordResetCodeMapper.insert(resetCode);

        try {
            mailService.sendPasswordResetCode(normalizedEmail, code);
        } catch (RuntimeException ex) {
            passwordResetCodeMapper.deleteById(resetCode.getId());
            throw new IllegalStateException("发送验证码邮件失败", ex);
        }
    }

    /**
     * 校验验证码并重置密码.
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(String email, String code, String newPassword) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedCode = normalizeCode(code);
        User user = findByEmail(normalizedEmail);
        LocalDateTime now = LocalDateTime.now();

        if (user == null) {
            throw new IllegalArgumentException("验证码无效或已过期");
        }

        PasswordResetCode resetCode = passwordResetCodeMapper.selectLatestValidByEmail(normalizedEmail, now, maxAttempts);
        if (resetCode == null || !user.getId().equals(resetCode.getUserId())) {
            throw new IllegalArgumentException("验证码无效或已过期");
        }

        if (!passwordEncoder.matches(normalizedCode, resetCode.getCodeHash())) {
            int nextAttemptCount = resetCode.getAttemptCount() + 1;
            resetCode.setAttemptCount(nextAttemptCount);
            if (nextAttemptCount >= maxAttempts) {
                resetCode.setConsumedAt(now);
            }
            passwordResetCodeMapper.updateById(resetCode);
            throw new IllegalArgumentException("验证码错误");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        resetCode.setConsumedAt(now);
        passwordResetCodeMapper.updateById(resetCode);
    }

    private User findByEmail(String email) {
        return userMapper.selectOne(new QueryWrapper<User>().eq("email", email));
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        return email.trim();
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("验证码不能为空");
        }
        return code.trim();
    }

    /**
     * 生成6位数字验证码.
     */
    private String generateCode() {
        int code = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return String.valueOf(code);
    }
}
