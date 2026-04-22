package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.data.dto.AdminAssessRecordView;
import org.wlow.iqabackend.data.dto.UserInfo;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.data.entity.User;
import org.wlow.iqabackend.exception.AdminAccessDeniedException;
import org.wlow.iqabackend.exception.AssessRecordNotFoundException;
import org.wlow.iqabackend.exception.UserNotFoundException;
import org.wlow.iqabackend.mapper.AssessRecordMapper;
import org.wlow.iqabackend.mapper.FileEntryMapper;
import org.wlow.iqabackend.mapper.PasswordResetCodeMapper;
import org.wlow.iqabackend.mapper.UserMapper;
import org.wlow.iqabackend.util.Common;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 管理员服务.
 * <p>
 * 第一阶段集中承载三类能力:
 * 1. 用户分页查询 / 角色修改 / 批量删除
 * 2. 全站评估历史分页查询 / 批量删除
 * 3. 管理员侧的统一权限兜底判断
 */
@Service
@RequiredArgsConstructor
public class AdminService {
    private final UserMapper userMapper;
    private final AssessRecordMapper assessRecordMapper;
    private final PasswordResetCodeMapper passwordResetCodeMapper;
    private final FileEntryMapper fileEntryMapper;
    private final FileService fileService;

    /**
     * 分页查询用户列表.
     * <p>
     * 约定:
     * 1. username/email/role 为空时不参与筛选
     * 2. username/email 使用模糊匹配, 更符合后台"搜索"语义
     * 3. role 使用精确匹配, 因为角色本身是枚举/标识型字段
     */
    public Page<UserInfo> listUsers(int page, int pageSize, String username, String email, String role) {
        requireAdminRole();

        QueryWrapper<User> wrapper = new QueryWrapper<User>()
                .like(hasText(username), "username", username == null ? null : username.trim())
                .like(hasText(email), "email", email == null ? null : email.trim())
                .eq(hasText(role), "role", role == null ? null : UserRole.normalize(role))
                .orderByDesc("register_time");

        Page<User> pageRequest = new Page<>(page, pageSize);
        Page<User> pageResult = userMapper.selectPage(pageRequest, wrapper);
        List<UserInfo> records = pageResult.getRecords().stream()
                .map(this::toUserInfo)
                .toList();
        return copyPage(pageResult, records);
    }

    /**
     * 修改指定用户角色.
     * <p>
     * 角色仍然存字符串, 这样后续即使新增"图像评估专家"等角色也不用改表结构.
     * 当前这里只要求非空, ADMIN权限判断依然只认ADMIN本身.
     */
    public void updateUserRole(Integer userId, String role) {
        requireAdminRole();
        String normalizedRole = normalizeTargetRole(role);

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new UserNotFoundException("目标用户不存在");
        }

        user.setRole(normalizedRole);
        userMapper.updateById(user);
    }

    /**
     * 批量删除用户.
     * <p>
     * 删除顺序固定为:
     * 1. 查询待删用户和关联资源
     * 2. 删除评估历史
     * 3. 删除文件元数据与物理文件
     * 4. 删除密码重置记录
     * 5. 删除用户
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteUsers(List<Integer> ids) {
        requireAdminRole();
        List<Integer> userIds = Common.normalizeIds(ids);

        List<User> users = Optional.ofNullable(userMapper.selectList(new QueryWrapper<User>().in("id", userIds)))
                .orElse(List.of());
        if (users.size() != userIds.size()) {
            throw new UserNotFoundException("存在不存在的用户");
        }

        List<AssessRecord> assessRecords = Optional.ofNullable(assessRecordMapper.selectByUserIdList(userIds))
                .orElse(List.of());
        List<FileEntry> fileEntries = Optional.ofNullable(fileEntryMapper.selectList(new QueryWrapper<FileEntry>().in("user_id", userIds)))
                .orElse(List.of());

        if (!assessRecords.isEmpty()) {
            assessRecordMapper.delete(new QueryWrapper<AssessRecord>().in("user_id", userIds));
        }

        for (FileEntry fileEntry : fileEntries) {
            fileService.deleteFileEntry(fileEntry);
        }

        if (!userIds.isEmpty()) {
            // 密码重置记录本质上是用户附属数据, 管理员删用户时应该一起清空.
            passwordResetCodeMapper.deleteByUserIdList(userIds);
        }

        // 这里按主键逐个删除用户, 主要是为了让删除计数和"已校验存在的用户集合"保持一一对应.
        // 在事务保护下, 即使中途某一步抛异常, 数据库侧仍会整体回滚.
        int deletedUsers = 0;
        for (Integer userId : userIds) {
            deletedUsers += userMapper.deleteById(userId);
        }
        if (deletedUsers != userIds.size()) {
            throw new IllegalStateException("删除用户失败");
        }
        return deletedUsers;
    }

    /**
     * 分页查询全站评估历史.
     * <p>
     * 所有筛选项都允许为空:
     * - userId 为空时查询所有用户
     * - filename 为空时不按文件名筛选
     * - startTime/endTime 为空时不加时间上下界
     */
    public Page<AdminAssessRecordView> listAssessHistory(int page,
                                                         int pageSize,
                                                         Integer userId,
                                                         String filename,
                                                         LocalDateTime startTime,
                                                         LocalDateTime endTime) {
        requireAdminRole();
        validateTimeRange(startTime, endTime);

        QueryWrapper<AssessRecord> wrapper = new QueryWrapper<AssessRecord>()
                .eq(userId != null, "user_id", userId)
                .like(hasText(filename), "filename", filename == null ? null : filename.trim())
                .ge(startTime != null, "assess_time", startTime)
                .le(endTime != null, "assess_time", endTime)
                .orderByDesc("assess_time");

        Page<AssessRecord> pageRequest = new Page<>(page, pageSize);
        Page<AssessRecord> pageResult = assessRecordMapper.selectPage(pageRequest, wrapper);
        List<AdminAssessRecordView> records = fillAdminHistoryViews(pageResult.getRecords());
        return copyPage(pageResult, records);
    }

    /**
     * 批量删除任意用户的评估历史.
     * <p>
     * 管理员删除时不再做"当前用户归属校验", 但仍维持严格原子语义:
     * 只要请求里存在任意不存在的历史记录id, 整批都失败.
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteAssessHistory(List<Integer> ids) {
        requireAdminRole();
        List<Integer> historyIds = Common.normalizeIds(ids);

        List<AssessRecord> records = Optional.ofNullable(assessRecordMapper.selectByIdList(historyIds))
                .orElse(List.of());
        if (records.size() != historyIds.size()) {
            throw new AssessRecordNotFoundException("存在不存在的评估历史记录");
        }

        List<Integer> fileEntryIds = records.stream()
                .map(AssessRecord::getFileEntryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<FileEntry> fileEntries = fileEntryIds.isEmpty()
                ? List.of()
                : Optional.ofNullable(fileEntryMapper.selectList(new QueryWrapper<FileEntry>().in("id", fileEntryIds)))
                .orElse(List.of());

        int deletedRecords = assessRecordMapper.delete(new QueryWrapper<AssessRecord>().in("id", historyIds));
        if (deletedRecords != historyIds.size()) {
            throw new IllegalStateException("删除评估历史记录失败");
        }

        for (FileEntry fileEntry : fileEntries) {
            fileService.deleteFileEntry(fileEntry);
        }
        return deletedRecords;
    }

    /**
     * 服务层兜底校验管理员角色.
     * <p>
     * 路由层已经有AdminInterceptor, 这里仍然保留兜底判断,
     * 避免未来有其它调用链路直接复用AdminService时忘记做权限限制.
     */
    private void requireAdminRole() {
        if (!UserRole.isAdmin(CurrentUser.getRole())) {
            throw new AdminAccessDeniedException("无权访问管理员功能");
        }
    }

    private UserInfo toUserInfo(User user) {
        return new UserInfo(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                UserRole.normalize(user.getRole()),
                user.getRegisterTime()
        );
    }

    /**
     * 批量把历史记录转换成管理员视图.
     * 这里额外补齐用户名和图片访问URL, 让前端拿到分页结果后就能直接展示.
     * 图片URL已经改成静态资源路径, 因此这里需要批量查file_entry拿到uuid和扩展名.
     */
    private List<AdminAssessRecordView> fillAdminHistoryViews(List<AssessRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        Map<Integer, String> usernameMap = buildUsernameMap(records);
        Map<Integer, FileEntry> fileEntryMap = buildFileEntryMap(records);
        return records.stream()
                .map(record -> new AdminAssessRecordView(
                        record.getId(),
                        record.getUserId(),
                        usernameMap.get(record.getUserId()),
                        record.getFilename(),
                        record.getFileEntryId(),
                        Optional.ofNullable(fileEntryMap.get(record.getFileEntryId()))
                                .map(FileEntry::getResourceURL)
                                .orElse(null),
                        record.getAssessTime(),
                        record.getQualityScore(),
                        record.getScene(),
                        record.getDistortions()
                ))
                .toList();
    }

    /**
     * 按历史记录中的userId批量查询用户名.
     * 这里使用一次selectList批量查用户, 避免每条历史记录单独查库.
     */
    private Map<Integer, String> buildUsernameMap(List<AssessRecord> records) {
        List<Integer> userIds = records.stream()
                .map(AssessRecord::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }

        return Optional.ofNullable(userMapper.selectList(new QueryWrapper<User>().in("id", userIds)))
                .orElse(List.of())
                .stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (left, right) -> left, LinkedHashMap::new));
    }

    /**
     * 按历史记录里的fileEntryId批量查询文件元数据.
     * 管理员历史列表也需要返回新的静态imageURL, 所以这里统一把file_entry转换成resourceURL映射.
     */
    private Map<Integer, FileEntry> buildFileEntryMap(List<AssessRecord> records) {
        List<Integer> fileEntryIds = records.stream()
                .map(AssessRecord::getFileEntryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fileEntryIds.isEmpty()) {
            return Map.of();
        }
        return fileService.mapFileEntriesByIds(fileEntryIds);
    }

    /**
     * 统一复制分页元数据.
     * 这样无论是用户分页还是管理员历史分页, 都能复用同一套转换逻辑.
     */
    private <S, T> Page<T> copyPage(Page<S> source, List<T> records) {
        Page<T> target = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        target.setRecords(records);
        return target;
    }

    /**
     * 校验待设置的新角色.
     * 这里不把角色限制死成固定枚举, 只要求非空, 同时对当前已知角色做标准化大小写处理.
     */
    private String normalizeTargetRole(String role) {
        if (!hasText(role)) {
            throw new IllegalArgumentException("角色不能为空");
        }
        return UserRole.normalize(role);
    }

    /**
     * 时间范围查询的基本校验.
     * 开始时间和结束时间都允许为空, 但如果同时提供则要求开始时间不能晚于结束时间.
     */
    private void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("startTime不能晚于endTime");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
