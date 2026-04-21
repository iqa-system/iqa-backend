package org.wlow.iqabackend.util;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 通用工具类.
 * <p>
 * 这里存放多个业务模块都会复用, 且不依赖具体Service状态的纯工具逻辑.
 * 当前先提取"批量删除接口常用的id数组归一化"能力, 避免AdminService和AssessService各自维护一份重复实现.
 */
public final class Common {

    private Common() {
        // 工具类不需要实例化.
    }

    /**
     * 归一化批量操作中传入的id数组.
     * <p>
     * 统一规则:
     * 1. ids不能为空或空数组
     * 2. 数组元素不能为null
     * 3. id必须大于0
     * 4. 自动去重, 保证批量删除/查询时计数稳定
     */
    public static List<Integer> normalizeIds(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids不能为空");
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("id不能为空");
        }
        if (ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("id必须大于0");
        }

        List<Integer> normalizedIds = ids.stream()
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            throw new IllegalArgumentException("ids不能为空");
        }
        return normalizedIds;
    }
}
