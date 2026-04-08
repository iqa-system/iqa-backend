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
     * 查询指定用户的全部关联文件主键.
     */
    @Select("SELECT file_entry_id FROM assess_record WHERE user_id = #{userId}")
    List<Integer> selectFileEntryIdsByUserId(@Param("userId") Integer userId);

    /**
     * 删除指定用户的全部评估历史记录.
     */
    @Delete("DELETE FROM assess_record WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Integer userId);
}

