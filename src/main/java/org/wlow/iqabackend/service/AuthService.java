package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
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
     * 注册新用户, 成功后返回新的AccessToken.
     */
    public Response<String> signUp(String username, String rawPassword) {
        if (existsUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRegisterTime(LocalDateTime.now());
        userMapper.insert(user);
        return Response.success(username + "注册成功");
    }

    /**
     * 登录校验账号密码, 成功后返回AccessToken.
     */
    public AuthTokenResponse login(String username, String rawPassword) {
        User user = findByUsername(username);
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return buildTokenResponse(username, user.getId());
    }

    /**
     * 刷新AccessToken, 旧Token需有效.
     */
    public AuthTokenResponse refresh(String accessToken) {
        Claims claims = jwtService.parseAndValidateAccessToken(accessToken);
        String username = claims.get("username", String.class);
        Integer userId = claims.get("userId", Integer.class);
        return buildTokenResponse(username, userId);
    }

    /**
     * 判断用户名是否已存在.
     */
    private boolean existsUsername(String username) {
        return userMapper.selectOne(new QueryWrapper<User>().eq("username", username)) != null;
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
    private AuthTokenResponse buildTokenResponse(String username, Integer userId) {
        String token = jwtService.generateAccessToken(username, userId);
        return new AuthTokenResponse(token, "Access", accessTokenExpiration);
    }
}

