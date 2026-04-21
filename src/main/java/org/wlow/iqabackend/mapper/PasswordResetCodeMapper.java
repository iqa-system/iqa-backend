package org.wlow.iqabackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.wlow.iqabackend.data.entity.PasswordResetCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 密码重置验证码表Mapper.
 */
@Mapper
public interface PasswordResetCodeMapper extends BaseMapper<PasswordResetCode> {
    /**
     * 查询指定邮箱最近一次发送的验证码记录.
     * 主要用于发送冷却时间判断.
     */
    @Select("""
            SELECT id, user_id, email, code_hash, expires_at, attempt_count, consumed_at, created_at
            FROM password_reset_code
            WHERE email = #{email}
            ORDER BY created_at DESC
            LIMIT 1
            """)
    PasswordResetCode selectLatestByEmail(@Param("email") String email);

    /**
     * 查询指定邮箱最新一条仍可用于重置的验证码记录.
     */
    @Select("""
            SELECT id, user_id, email, code_hash, expires_at, attempt_count, consumed_at, created_at
            FROM password_reset_code
            WHERE email = #{email}
              AND consumed_at IS NULL
              AND expires_at > #{now}
              AND attempt_count < #{maxAttempts}
            ORDER BY created_at DESC
            LIMIT 1
            """)
    PasswordResetCode selectLatestValidByEmail(@Param("email") String email,
                                               @Param("now") LocalDateTime now,
                                               @Param("maxAttempts") Integer maxAttempts);

    /**
     * 将指定邮箱当前所有未消费验证码标记为已失效.
     */
    @Update("""
            UPDATE password_reset_code
            SET consumed_at = #{consumedAt}
            WHERE email = #{email}
              AND consumed_at IS NULL
            """)
    int consumeActiveCodesByEmail(@Param("email") String email, @Param("consumedAt") LocalDateTime consumedAt);

    /**
     * 按用户主键集合批量删除密码重置记录.
     * 管理员删除用户时会复用这个窄接口, 避免把"按user_id批量清理"的语义藏在通用Wrapper里.
     */
    @Delete({
            "<script>",
            "DELETE FROM password_reset_code",
            "WHERE user_id IN",
            "<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>",
            "#{userId}",
            "</foreach>",
            "</script>"
    })
    int deleteByUserIdList(@Param("userIds") List<Integer> userIds);
}
