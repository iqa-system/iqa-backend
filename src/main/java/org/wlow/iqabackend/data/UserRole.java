package org.wlow.iqabackend.data;

/**
 * 用户角色枚举.
 * <p>
 * 这里保留枚举常量, 主要是为了复用当前已经稳定下来的 USER / ADMIN / EXPERT 三个基础角色.
 * 但本项目的role字段本质上仍然是"字符串角色", 后续允许继续扩展出新的角色类型,
 * 例如图像评估专家, 审核员等. 因此这里不能再像旧版本那样把未知角色一律压成USER,
 * 否则未来新增角色一旦登录, 角色信息就会在鉴权链路里丢失.
 */
public enum UserRole {
    USER,
    ADMIN,
    EXPERT;

    /**
     * 对数据库或Token中读取到的角色值做最小化归一.
     * <p>
     * 归一规则:
     * 1. 空值仍兜底成USER, 保证旧数据和异常Token不至于让角色字段为null
     * 2. 对当前已知角色USER/ADMIN/EXPERT做大小写归一, 统一成标准大写
     * 3. 对未知角色原样保留(只做trim), 为后续扩展角色预留空间
     */
    public static String normalize(String role) {
        if (role == null || role.isBlank()) {
            return USER.name();
        }
        String normalizedRole = role.trim();
        for (UserRole userRole : values()) {
            if (userRole.name().equalsIgnoreCase(normalizedRole)) {
                return userRole.name();
            }
        }
        return normalizedRole;
    }

    /**
     * 判断某个角色字符串是否具备管理员权限.
     * <p>
     * 当前管理员接口权限规则固定为"只有ADMIN角色可访问".
     * 未来即使扩展出更多角色类型, 这里也只负责判断是否为管理员,
     * 不会把其它角色自动视为管理员.
     */
    public static boolean isAdmin(String role) {
        return ADMIN.name().equalsIgnoreCase(normalize(role));
    }

    /**
     * 判断某个角色字符串是否具备图像评估专家权限.
     * <p>
     * 当前主观评分能力只开放给EXPERT角色.
     * 这里和管理员判断一样, 只识别明确的EXPERT, 不会把其它扩展角色误判成专家.
     */
    public static boolean isExpert(String role) {
        return EXPERT.name().equalsIgnoreCase(normalize(role));
    }

    /**
     * 判断某个角色是否允许前台自助注册.
     * <p>
     * 当前只开放普通用户和评估专家自助注册.
     * 管理员以及其它扩展角色都必须走后台管理链路, 不能从公开注册页直接创建.
     */
    public static boolean isSelfRegisterRole(String role) {
        String normalizedRole = normalize(role);
        return USER.name().equals(normalizedRole) || EXPERT.name().equals(normalizedRole);
    }
}
