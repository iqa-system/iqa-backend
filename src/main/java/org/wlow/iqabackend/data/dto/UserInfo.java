package org.wlow.iqabackend.data.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户信息响应体.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {
    /**
     * 用户ID.
     */
    private Integer id;

    /**
     * 用户名.
     */
    private String username;

    /**
     * 注册时间.
     */
    private LocalDateTime registerTime;
}
