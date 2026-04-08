package org.wlow.iqabackend.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.wlow.iqabackend.data.dto.Response;
import org.wlow.iqabackend.data.dto.AuthTokenResponse;
import org.wlow.iqabackend.service.AuthService;
import org.wlow.iqabackend.service.PasswordResetService;

/**
 * 认证控制器, 提供注册/登录/刷新Token接口.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {
    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    /**
     * 用户注册接口.
     */
    @PostMapping("/sign-up")
    public Response<String> signUp(
            @RequestParam @NotBlank(message = "用户名不能为空")
            @Size(min = 3, max = 30, message = "用户名长度需在3-30之间") String username,
            @RequestParam @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            @Size(max = 255, message = "邮箱长度不能超过255") String email,
            @RequestParam @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 64, message = "密码长度需在6-64之间") String password) {
        try {
            return authService.signUp(username, password, email);
        } catch (IllegalArgumentException ex) {
            log.warn("注册失败: {}", ex.getMessage());
            return Response.failure(400, ex.getMessage());
        }
    }

    /**
     * 用户登录接口.
     */
    @PostMapping("/login")
    public Response<AuthTokenResponse> login(
            @RequestParam @NotBlank(message = "用户名不能为空") String username,
            @RequestParam @NotBlank(message = "密码不能为空") String password) {
        try {
            AuthTokenResponse tokenResponse = authService.login(username, password);
            return Response.success("登录成功", tokenResponse);
        } catch (IllegalArgumentException ex) {
            log.warn("登录失败: {}", ex.getMessage());
            return Response.failure(400, ex.getMessage());
        }
    }

    /**
     * 刷新AccessToken接口, 旧Token必须有效.
     */
    @PostMapping("/refresh")
    public Response<AuthTokenResponse> refresh(
            @RequestParam @NotBlank(message = "AccessToken不能为空") String accessToken) {
        try {
            AuthTokenResponse tokenResponse = authService.refresh(accessToken);
            return Response.success("刷新成功", tokenResponse);
        } catch (Exception ex) {
            log.warn("刷新Token失败: {}", ex.getMessage());
            return Response.failure(401, "Token无效或已过期");
        }
    }

    /**
     * 发送找回密码验证码.
     * 无论邮箱是否存在都返回统一成功文案, 防止枚举系统中的注册邮箱.
     */
    @PostMapping("/password-reset/send-code")
    public Response<String> sendPasswordResetCode(
            @RequestParam @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            @Size(max = 255, message = "邮箱长度不能超过255") String email) {
        passwordResetService.sendResetCode(email);
        return Response.success("如果邮箱存在，验证码已发送");
    }

    /**
     * 提交验证码并重置密码.
     */
    @PostMapping("/password-reset/reset")
    public Response<String> resetPassword(
            @RequestParam @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            @Size(max = 255, message = "邮箱长度不能超过255") String email,
            @RequestParam @NotBlank(message = "验证码不能为空")
            @Pattern(regexp = "\\d{6}", message = "验证码必须为6位数字") String code,
            @RequestParam @NotBlank(message = "新密码不能为空")
            @Size(min = 6, max = 64, message = "新密码长度需在6-64之间") String newPassword) {
        try {
            passwordResetService.resetPassword(email, code, newPassword);
            return Response.success("密码重置成功");
        } catch (IllegalArgumentException ex) {
            log.warn("密码重置失败: {}", ex.getMessage());
            return Response.failure(400, ex.getMessage());
        }
    }
}
