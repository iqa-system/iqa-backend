package org.wlow.iqabackend.data;

/**
 * 用户角色枚举.
 * 当前版本只支持普通用户和管理员两种角色.
 */
public enum UserRole {
    USER,
    ADMIN;

    /**
     * 对数据库或旧Token中读取到的角色值做兜底归一化.
     * 未设置或非法值都按USER处理, 便于兼容历史数据.
     */
    public static String normalize(String role) {
        if (role == null || role.isBlank()) {
            return USER.name();
        }
        for (UserRole userRole : values()) {
            if (userRole.name().equalsIgnoreCase(role.trim())) {
                return userRole.name();
            }
        }
        return USER.name();
    }
}
