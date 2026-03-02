package org.wlow.iqabackend.data;

/**
 * 当前用户信息. 采用ThreadLocal存储, 适用于每次请求一个用户的场景. 需要在请求开始时设置用户信息, 请求结束时清除用户信息(token拦截器内), 避免内存泄漏.
 */
public class CurrentUser {
    private static final ThreadLocal<Integer> ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    public static void setId(Integer id) {
        ID.set(id);
    }
    public static void setUsername(String username) {
        USERNAME.set(username);
    }
    public static void setAll(Integer id, String username) {
        ID.set(id);
        USERNAME.set(username);
    }
    public static void clearAll() {
        ID.remove();
        USERNAME.remove();
    }
    public static Integer getId() {
        return ID.get();
    }
    public static String getUsername() {
        return USERNAME.get();
    }
}
