package org.wlow.iqabackend.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.wlow.iqabackend.data.dto.Response;
import org.wlow.iqabackend.data.dto.UserInfo;
import org.wlow.iqabackend.service.UserService;

/**
 * 用户信息管理接口.
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Validated
public class UserController {
    private final UserService userService;

    /**
     * 获取当前用户信息接口.
     */
    @GetMapping("/info")
    public Response<UserInfo> getInfo() {
        UserInfo userInfo = userService.getUserInfo();
        return Response.success(userInfo);
    }

    /**
     * 更新用户信息接口.
     * 支持修改用户名, 邮箱和密码, 修改密码时需要提供旧密码.
     */
    @PostMapping("/update-info")
    public Response<String> updateInfo(
            @RequestParam(required = false) @Size(min = 3, max = 50, message = "用户名长度必须在3-50之间") String username,
            @RequestParam(required = false) @Email(message = "邮箱格式不正确")
            @Size(max = 255, message = "邮箱长度不能超过255") String email,
            @RequestParam(required = false) @Size(min = 6, message = "旧密码长度至少为6") String oldPassword,
            @RequestParam(required = false) @Size(min = 6, message = "新密码长度至少为6") String newPassword) {
        userService.updateUserInfo(username, email, oldPassword, newPassword);
        return Response.success("用户信息更新成功");
    }
}
