package org.wlow.iqabackend.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 密码重置验证码实体.
 * 用于保存邮箱验证码的哈希、过期时间和使用状态.
 */
@Data
@TableName("password_reset_code")
public class PasswordResetCode {
    @TableId(type = IdType.AUTO)
    private Integer id;

    @TableField("user_id")
    private Integer userId;

    private String email;

    @TableField("code_hash")
    private String codeHash;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("consumed_at")
    private LocalDateTime consumedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
