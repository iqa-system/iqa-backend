package org.wlow.iqabackend.data.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Response<Type> {
    private int code;
    private String message;
    private Type data;

    // 静态方法, 不能直接访问类泛型, 所以得是泛型方法才能自动推断类型
    public static <T> Response<T> ok() {
        return new Response<>(200, "ok", null);
    }

    public static <T> Response<T> success(String message, T data) {
        return new Response<>(200, message, data);
    }

    public static <T> Response<T> success(T data) {
        return new Response<>(200, "ok", data);
    }

    public static <T> Response<T> failure(int code, String message) {
        return new Response<>(code, message, null);
    }

    public static <T> Response<T> error(String message) {
        return new Response<>(500, message, null);
    }

    public static <T> Response<T> forbidden(String message) {
        return new Response<>(403, message, null);
    }

    public static <T> Response<T> forbidden() {
        return new Response<>(403, "无权限", null);
    }
}
