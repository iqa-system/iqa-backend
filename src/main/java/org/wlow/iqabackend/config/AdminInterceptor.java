package org.wlow.iqabackend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.data.dto.Response;

import java.nio.charset.StandardCharsets;

/**
 * 管理员接口权限拦截器.
 * <p>
 * 设计目的:
 * 1. 所有/admin/** 路由统一在进入Controller前完成管理员权限校验
 * 2. 普通用户访问管理员接口时直接返回403, 避免每个Controller重复写权限判断
 * 3. 这里依赖AuthInterceptor已经提前完成JWT校验并写入CurrentUser
 */
@Slf4j
@Component
@NullMarked
public class AdminInterceptor implements HandlerInterceptor {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 预检请求不做角色限制, 避免跨域场景下OPTIONS请求被拦截.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (UserRole.isAdmin(CurrentUser.getRole())) {
            return true;
        }

        log.warn("普通用户尝试访问管理员接口: userId={}, path={}", CurrentUser.getId(), request.getRequestURI());
        writeForbidden(response, "无权访问管理员接口");
        return false;
    }

    /**
     * 统一输出403 JSON响应.
     * <p>
     * 这里不抛异常而是直接写响应, 是因为拦截器阶段最适合"就地拦截"并终止后续处理链.
     */
    private void writeForbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = objectMapper.writeValueAsString(Response.failure(403, message));
        response.getWriter().write(body);
    }
}
