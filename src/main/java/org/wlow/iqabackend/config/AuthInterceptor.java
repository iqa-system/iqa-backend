package org.wlow.iqabackend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.dto.Response;
import org.wlow.iqabackend.service.JwtService;

import java.nio.charset.StandardCharsets;

/**
 * JWT鉴权拦截器, 校验AccessToken有效性.
 */
@Slf4j
@Component
@NullMarked
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行预检请求, 避免跨域场景被鉴权拦截
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("Authorization");
        if (token == null || token.isBlank()) {
            writeUnauthorized(response, "未登录或缺少Token");
            return false;
        }
        try {
            Claims payload = jwtService.parseAndValidateAccessToken(token);
            String username = payload.get("username", String.class);
            int userId = payload.get("id", Integer.class);
            CurrentUser.setAll(userId, username);
            return true;
        } catch (Exception ex) {
            log.warn("Token校验失败: {}", ex.getMessage());
            writeUnauthorized(response, "Token无效或已过期");
            return false;
        }
    }

    /**
     * 清除当前用户信息, 避免线程复用导致数据泄露.
      */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) {
        // 请求处理完成后清除当前用户信息, 避免线程复用导致信息泄露
        CurrentUser.clearAll();
    }

    /**
     * 统一输出未授权响应, 确保中文不乱码.
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = objectMapper.writeValueAsString(Response.failure(401, message));
        response.getWriter().write(body);
    }
}
