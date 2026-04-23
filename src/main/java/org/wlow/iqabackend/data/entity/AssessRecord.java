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

import java.math.BigDecimal;
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
     * 实际执行评估的模型ID.
     * 历史记录始终保存Python最终返回的model_id, 便于后续区分不同模型的结果来源.
     */
    @TableField("model_id")
    private String modelId;

    /**
     * 评估专家填写的主观分数.
     * 和模型分共用0-5量纲, 使用DECIMAL(3,2)持久化, 方便直接对比.
     */
    @TableField("expert_score")
    private BigDecimal expertScore;

    /**
     * 专家主观分最后一次填写或修改时间.
     */
    @TableField("expert_score_time")
    private LocalDateTime expertScoreTime;

    /**
     * 场景分类编码.
     */
    private Integer scene;

    /**
     * 失真类型概率列表.
     * 使用JSON列存储, 查询时自动反序列化为对象列表.
     * 非LIQE模型可能不返回失真信息, 因此字段允许为null.
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<DistortionProb> distortions;
}
