package org.wlow.iqabackend.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件元数据实体.
 * 对应file_entry表, 保存图片在本地文件系统中的落盘信息.
 *
 * 字段拆分方式说明:
 * 1. directory 保存父目录绝对路径
 * 2. filename 保存UUID文件名(不带后缀)
 * 3. extname 保存不带点的小写扩展名
 * 读取物理文件时再把三者拼回完整路径
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("file_entry")
public class FileEntry {
    /**
     * 文件元数据主键.
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 上传者用户ID.
     */
    private Integer userId;

    /**
     * UUID文件名(不带扩展名).
     */
    private String filename;

    /**
     * 文件扩展名(不带点, 小写).
     */
    private String extname;

    /**
     * 文件父目录绝对路径.
     */
    private String directory;

    /**
     * 文件大小, 单位为字节.
     */
    private Long size;

    /**
     * 文件上传时间.
     */
    @TableField("upload_time")
    private LocalDateTime uploadTime;

    /**
     * 资源访问相对路径.
     * 查询时动态拼接, 不存储到数据库.
     */
    @TableField(exist = false)
    private String resourceURL;
}
