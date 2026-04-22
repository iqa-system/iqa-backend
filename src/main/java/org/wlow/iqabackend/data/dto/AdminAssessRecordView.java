package org.wlow.iqabackend.data.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员历史记录视图.
 * <p>
 * 和普通用户看到的AssessRecord相比, 管理员视图额外补充了用户名字段,
 * 方便后台直接定位某条评估记录属于哪个用户, 减少前端再发一次用户查询请求.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminAssessRecordView {
    /**
     * 历史记录主键.
     */
    private Integer id;

    /**
     * 记录所属用户ID.
     */
    private Integer userId;

    /**
     * 记录所属用户名.
     */
    private String username;

    /**
     * 原始上传文件名.
     */
    private String filename;

    /**
     * 对应文件元数据主键.
     */
    private Integer fileEntryId;

    /**
     * 图片资源访问路径.
     * 新版本统一返回静态资源路径/resource/image/{uuid}.{ext}, 管理员和普通用户使用同一种访问方式.
     */
    private String imageURL;

    /**
     * 评估时间.
     */
    private LocalDateTime assessTime;

    /**
     * 图像质量分数.
     */
    private Double qualityScore;

    /**
     * 专家主观评分.
     * 和模型分使用同一量纲, 方便后台直接对照查看.
     */
    private BigDecimal expertScore;

    /**
     * 专家主观评分最后一次填写或修改时间.
     */
    private LocalDateTime expertScoreTime;

    /**
     * 场景分类编码.
     */
    private Integer scene;

    /**
     * 失真类型概率列表.
     */
    private List<DistortionProb> distortions;
}
