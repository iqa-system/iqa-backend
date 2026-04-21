package org.wlow.iqabackend.exception;

/**
 * 用户不存在异常.
 * <p>
 * 主要用于管理员侧的用户修改/删除链路, 明确把"目标用户不存在"映射成404,
 * 而不是落入通用的400非法参数或500服务端异常.
 */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String message) {
        super(message);
    }
}
