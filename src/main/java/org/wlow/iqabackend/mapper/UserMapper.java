package org.wlow.iqabackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.wlow.iqabackend.data.entity.User;

/**
 * 用户表基础CRUD Mapper.
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}

