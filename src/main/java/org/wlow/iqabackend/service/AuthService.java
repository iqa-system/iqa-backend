package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.data.dto.AuthTokenResponse;
import org.wlow.iqabackend.data.dto.Response;
import org.wlow.iqabackend.data.entity.User;
import org.wlow.iqabackend.mapper.UserMapper;

import java.time.LocalDateTime;

/**
 * 认证服务, 处理注册/登录/刷新逻辑.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    @Value("${jwt.expiration.access-token}")
    private long accessTokenExpiration;

    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 注册新用户.
     * 当前版本注册成功后仅返回成功消息, 不直接发放Token.
     */
    public Response<String> signUp(String username, String rawPassword, String email) {
        String normalizedUsername = normalizeRequiredValue(username, "用户名不能为空");
        String normalizedEmail = normalizeRequiredValue(email, "邮箱不能为空");

        if (existsUsername(normalizedUsername)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (existsEmail(normalizedEmail)) {
            throw new IllegalArgumentException("邮箱已存在");
        }
        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setEmail(normalizedEmail);
        user.setRole(UserRole.USER.name());
        user.setRegisterTime(LocalDateTime.now());
        userMapper.insert(user);
        return Response.success(normalizedUsername + "注册成功");
    }

    /**
     * 登录校验账号密码, 成功后返回AccessToken.
     */
    public AuthTokenResponse login(String username, String rawPassword) {
        User user = findByUsername(normalizeRequiredValue(username, "用户名不能为空"));
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return buildTokenResponse(user);
    }

    /**
     * 刷新AccessToken, 旧Token需有效.
     */
    public AuthTokenResponse refresh(String accessToken) {
        Claims claims = jwtService.parseAndValidateAccessToken(accessToken);
        Integer userId = claims.get("userId", Integer.class);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return buildTokenResponse(user);
    }

    /**
     * 判断用户名是否已存在.
     */
    private boolean existsUsername(String username) {
        return userMapper.selectOne(new QueryWrapper<User>().eq("username", username)) != null;
    }

    /**
     * 判断邮箱是否已存在.
     */
    private boolean existsEmail(String email) {
        return userMapper.selectOne(new QueryWrapper<User>().eq("email", email)) != null;
    }

    /**
     * 根据用户名查询用户.
     */
    private User findByUsername(String username) {
        return userMapper.selectOne(new QueryWrapper<User>().eq("username", username));
    }

    /**
     * 统一构建Token返回体.
     */
    private AuthTokenResponse buildTokenResponse(User user) {
        // Token中的role直接沿用数据库角色值(仅对USER/ADMIN做标准化大小写),
        // 这样未来新增的扩展角色也能完整透传到前端和后续业务链路.
        String token = jwtService.generateAccessToken(
                user.getUsername(),
                user.getId(),
                UserRole.normalize(user.getRole())
        );
        return new AuthTokenResponse(token, "Access", accessTokenExpiration);
    }

    /**
     * 对用户名/邮箱这类必填字符串做trim处理.
     */
    private String normalizeRequiredValue(String value, String emptyMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        return value.trim();
    }
}

