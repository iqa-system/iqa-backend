package org.wlow.iqabackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.wlow.iqabackend.data.entity.AssessRecord;

import java.util.List;

/**
 * 评估历史记录表基础CRUD Mapper.
 */
@Mapper
public interface AssessRecordMapper extends BaseMapper<AssessRecord> {
    /**
     * 按主键集合查询评估历史记录.
     * 这里只查删除链路真正需要的字段, 避免引入与业务无关的列.
     */
    @Select({
            "<script>",
            "SELECT id, user_id, file_entry_id",
            "FROM assess_record",
            "WHERE id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    List<AssessRecord> selectByIdList(@Param("ids") List<Integer> ids);

    /**
     * 删除当前用户指定主键集合的评估历史记录.
     * user_id条件和id集合同时生效, 防止误删其他用户数据.
     */
    @Delete({
            "<script>",
            "DELETE FROM assess_record",
            "WHERE user_id = #{userId}",
            "AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    int deleteByUserIdAndIds(@Param("userId") Integer userId, @Param("ids") List<Integer> ids);
}

