package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.dto.UserInfo;
import org.wlow.iqabackend.data.entity.User;
import org.wlow.iqabackend.mapper.UserMapper;

/**
 * 用户信息管理服务.
 */
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 获取当前登录用户信息.
     */
    public UserInfo getUserInfo() {
        Integer userId = CurrentUser.getId();
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        return new UserInfo(user.getId(), user.getUsername(), user.getRegisterTime());
    }

    /**
     * 更新用户信息.
     * 支持修改用户名和密码, 修改密码时必须提供旧密码验证.
     */
    public void updateUserInfo(String username, String oldPassword, String newPassword) {
        Integer userId = CurrentUser.getId();
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        boolean hasUpdate = false;

        // 修改用户名
        if (username != null && !username.isBlank()) {
            if (username.equals(user.getUsername())) {
                throw new IllegalArgumentException("新用户名与当前用户名相同");
            }
            // 检查用户名是否已被占用
            if (existsUsername(username)) {
                throw new IllegalArgumentException("用户名已存在");
            }
            user.setUsername(username);
            hasUpdate = true;
        }

        // 修改密码
        if (newPassword != null && !newPassword.isBlank()) {
            // 验证旧密码
            if (oldPassword == null || oldPassword.isBlank()) {
                throw new IllegalArgumentException("修改密码时必须提供旧密码");
            }
            if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
                throw new IllegalArgumentException("旧密码不正确");
            }
            // 设置新密码
            user.setPassword(passwordEncoder.encode(newPassword));
            hasUpdate = true;
        }

        if (!hasUpdate) {
            throw new IllegalArgumentException("未提供任何需要更新的信息");
        }

        // 更新用户信息
        userMapper.updateById(user);
    }

    /**
     * 判断用户名是否已存在.
     */
    private boolean existsUsername(String username) {
        return userMapper.selectOne(new QueryWrapper<User>().eq("username", username)) != null;
    }
}
