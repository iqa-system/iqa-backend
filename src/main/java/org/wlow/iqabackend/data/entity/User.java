package org.wlow.iqabackend.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
     * 注册时间.
     */
    private LocalDateTime registerTime;
}

