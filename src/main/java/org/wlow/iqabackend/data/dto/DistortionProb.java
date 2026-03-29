package org.wlow.iqabackend.data.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 失真类型概率项.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DistortionProb {
    /**
     * 失真类型编码.
     */
    private Integer index;

    /**
     * 失真概率, 保留4位小数.
     */
    private Double prob;
}

