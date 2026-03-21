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
 * 图片评估历史记录实体.
 * 对应assess_record表, 用于持久化用户每次评估行为.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("assess_record")
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
     * 压缩后图片的Base64字符串.
     */
    private String base64;

    /**
     * 评估发生时间.
     */
    @TableField("assess_time")
    private LocalDateTime assessTime;
}

