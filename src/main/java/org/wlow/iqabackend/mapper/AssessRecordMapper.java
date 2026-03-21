package org.wlow.iqabackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.wlow.iqabackend.data.entity.AssessRecord;

/**
 * 评估历史记录表基础CRUD Mapper.
 */
@Mapper
public interface AssessRecordMapper extends BaseMapper<AssessRecord> {
}

