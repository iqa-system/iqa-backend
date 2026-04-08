package org.wlow.iqabackend.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.wlow.iqabackend.data.dto.DistortionProb;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 图片评估历史记录实体.
 * 对应assess_record表, 用于持久化用户每次评估行为.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName(value = "assess_record", autoResultMap = true)
public class AssessRecord {
    /**
     * 历史记录主键.
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 用户主键ID.
     */
    private Integer userId;

    /**
     * 上传时的原始文件名.
     */
    private String filename;

    /**
     * 对应文件元数据主键.
     * assess_record只和file_entry建立逻辑关联, 不再直接保存图片内容.
     */
    @TableField("file_entry_id")
    private Integer fileEntryId;

    /**
     * 图片资源访问地址.
     * 查询时动态拼接, 不存储到数据库.
     * 前端看到的是这个字段, 而不是磁盘绝对路径.
     */
    @TableField(exist = false)
    private String imageURL;

    /**
     * 评估发生时间.
     */
    @TableField("assess_time")
    private LocalDateTime assessTime;

    /**
     * 图像评估的质量分数.
     */
    private Double qualityScore;

    /**
     * 场景分类编码.
     */
    private Integer scene;

    /**
     * 失真类型概率列表.
     * 使用JSON列存储, 查询时自动反序列化为对象列表.
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<DistortionProb> distortions;
}
