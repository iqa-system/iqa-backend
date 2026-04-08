package org.wlow.iqabackend.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体, 对应user表.
 */
@Data
public class User {
    /**
     * 主键ID.
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 用户名, 全局唯一.
     */
    private String username;

    /**
     * 加密后的密码.
     */
    private String password;

    /**
     * 用户邮箱.
     * 对新注册用户要求必填且唯一; 为兼容旧数据, 历史记录中可能暂时为空.
     */
    private String email;

    /**
     * 用户角色.
     * 当前版本只支持USER和ADMIN.
     */
    private String role;

    /**
     * 注册时间.
     */
    private LocalDateTime registerTime;
}

