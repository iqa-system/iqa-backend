package org.wlow.iqabackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.wlow.iqabackend.data.entity.FileEntry;

/**
 * 文件元数据表基础CRUD Mapper.
 */
@Mapper
public interface FileEntryMapper extends BaseMapper<FileEntry> {
}
