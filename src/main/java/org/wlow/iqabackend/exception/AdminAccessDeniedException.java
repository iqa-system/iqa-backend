package org.wlow.iqabackend.exception;

/**
 * 管理员权限不足异常.
 * <p>
 * 管理员接口除了路由级拦截器外, 服务层也会再次做兜底校验.
 * 这样即使后续有其它调用路径绕过了/admin路由, 也不会把管理员能力误暴露给普通用户.
 */
public class AdminAccessDeniedException extends RuntimeException {
    public AdminAccessDeniedException(String message) {
        super(message);
    }
}
