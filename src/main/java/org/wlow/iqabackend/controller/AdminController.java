package org.wlow.iqabackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.wlow.iqabackend.data.dto.AdminAssessRecordView;
import org.wlow.iqabackend.data.dto.Response;
import org.wlow.iqabackend.data.dto.UserInfo;
import org.wlow.iqabackend.service.AdminService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理员接口控制器.
 * <p>
 * 当前控制器只承载第一期后台能力:
 * 1. 用户分页查询 / 角色修改 / 批量删除
 * 2. 全站评估历史分页查询 / 批量删除
 * 权限校验由AdminInterceptor统一处理, 这里专注于参数接收和响应封装.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Validated
public class AdminController {
    private final AdminService adminService;

    /**
     * 管理员分页查询用户列表.
     * 除分页参数外, 其它筛选字段都允许为空; 为空时表示不在该字段上加筛选条件.
     */
    @GetMapping("/users")
    public Response<Page<UserInfo>> listUsers(
            @RequestParam(defaultValue = "1") @Positive(message = "page必须大于0") Integer page,
            @RequestParam(defaultValue = "10") @Positive(message = "pageSize必须大于0") Integer pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String role) {
        Page<UserInfo> result = adminService.listUsers(page, pageSize, username, email, role);
        return Response.success(result);
    }

    /**
     * 管理员修改指定用户角色.
     * 新角色继续使用字符串传递, 为后续扩展其它角色类型预留空间.
     */
    @PostMapping("/users/{id}/role")
    public Response<String> updateUserRole(
            @PathVariable("id") @Positive(message = "id必须大于0") Integer userId,
            @RequestParam @NotBlank(message = "角色不能为空") String role) {
        adminService.updateUserRole(userId, role);
        return Response.success("用户角色更新成功");
    }

    /**
     * 管理员批量删除用户.
     * 请求体中的ids数组允许前端多选后一次性提交, 删除时会级联清理用户关联数据.
     */
    @DeleteMapping("/users")
    public Response<Integer> deleteUsers(@RequestBody Map<String, List<Integer>> request) {
        List<Integer> ids = request == null ? null : request.get("ids");
        int deletedCount = adminService.deleteUsers(ids);
        return Response.success(deletedCount);
    }

    /**
     * 管理员分页查询全站评估历史.
     * 所有筛选项都允许为空, 为空时不参与筛选.
     */
    @GetMapping("/assess/history")
    public Response<Page<AdminAssessRecordView>> listAssessHistory(
            @RequestParam(defaultValue = "1") @Positive(message = "page必须大于0") Integer page,
            @RequestParam(defaultValue = "10") @Positive(message = "pageSize必须大于0") Integer pageSize,
            @RequestParam(required = false) @Positive(message = "userId必须大于0") Integer userId,
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        Page<AdminAssessRecordView> result =
                adminService.listAssessHistory(page, pageSize, userId, filename, startTime, endTime);
        return Response.success(result);
    }

    /**
     * 管理员批量删除全站评估历史.
     * 删除时会同步清理历史记录, 文件元数据和本地磁盘文件.
     */
    @DeleteMapping("/assess/history")
    public Response<Integer> deleteAssessHistory(@RequestBody Map<String, List<Integer>> request) {
        List<Integer> ids = request == null ? null : request.get("ids");
        int deletedCount = adminService.deleteAssessHistory(ids);
        return Response.success(deletedCount);
    }
}
