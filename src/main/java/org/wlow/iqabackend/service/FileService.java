package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.exception.FileAccessDeniedException;
import org.wlow.iqabackend.exception.FileResourceNotFoundException;
import org.wlow.iqabackend.mapper.FileEntryMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文件存储服务.
 * 负责评估图片在本地文件系统中的上传, 查询和删除.
 * <p>
 * 设计约束:
 * 1. 数据库只保存文件元数据, 真实文件内容存磁盘.
 * 2. 每条评估记录对应一个独立文件, 不做去重或共享.
 * 3. 所有磁盘路径都必须落在storageRoot下, 防止路径穿越.
 * 4. 对外暴露的是虚拟资源地址/resource/image/{id}, 不暴露真实绝对路径.
 */
@Service
@Slf4j
public class FileService {
    private static final String IMAGE_RESOURCE_PREFIX = "/resource/image/";
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "bmp", "gif", "webp", "tif", "tiff");

    private final FileEntryMapper fileEntryMapper;
    private final Path storageRoot;

    /**
     * rootDir来自配置文件, 例如D:/code/.../upload.
     * 这里在启动时就转成绝对路径并做normalize, 避免后面每次拼路径都重复处理,
     * 同时也能统一路径格式, 便于后续做startsWith安全校验.
     */
    public FileService(FileEntryMapper fileEntryMapper,
                       @Value("${file-storage.root-dir}") String rootDir) {
        this.fileEntryMapper = fileEntryMapper;
        this.storageRoot = Paths.get(rootDir).toAbsolutePath().normalize();
    }

    /**
     * 上传MultipartFile并落盘到本地.
     * 这个方法主要是给同步Controller入口使用, 真正的落盘逻辑复用字节数组版本.
     */
    public FileEntry upload(MultipartFile file, Integer userId) {
        validateUserId(userId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file为空");
        }
        try {
            return upload(file.getBytes(), extractClientFilename(file.getOriginalFilename()), userId);
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取上传文件失败", ex);
        }
    }

    /**
     * 上传内存中的文件字节并保存元数据.
     */
    public FileEntry upload(byte[] bytes, String originalFilename, Integer userId) {
        validateUserId(userId);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("file为空");
        }

        String safeOriginalFilename = extractClientFilename(originalFilename);
        String extname = extractExtname(safeOriginalFilename);

        // 磁盘文件名统一改成UUID, 规避同名覆盖, 特殊字符和原始文件名泄露问题.
        String uuidFilename = UUID.randomUUID().toString();

        // 图片按用户分目录存储, 目录形如 <root>/image/{userId}.
        Path userDirectory = resolveUserDirectory(userId);
        Path storedPath = resolveStoredPath(userDirectory, uuidFilename, extname);

        try {
            // 目录不存在时自动创建; 已存在时不会报错.
            Files.createDirectories(userDirectory);
            Files.write(storedPath, bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("保存图片文件失败", ex);
        }

        FileEntry entry = new FileEntry();
        entry.setUserId(userId);
        entry.setFilename(uuidFilename);
        entry.setExtname(extname);
        entry.setDirectory(userDirectory.toString());
        entry.setSize((long) bytes.length);
        entry.setUploadTime(LocalDateTime.now());

        try {
            fileEntryMapper.insert(entry);
            return applyResourceURL(entry);
        } catch (RuntimeException ex) {
            // 数据库落库失败时回滚已写入的物理文件, 避免磁盘残留无主文件.
            rollbackPhysicalFile(storedPath, ex);
            throw ex;
        }
    }

    /**
     * 按当前用户批量查询文件元数据, 并回填资源URL.
     * 这个方法主要给AssessService.history使用, 避免对每条历史记录单独查库造成N+1问题.
     */
    public Map<Integer, FileEntry> mapOwnedFileEntriesByIds(Integer userId, Collection<Integer> fileEntryIds) {
        validateUserId(userId);
        if (fileEntryIds == null || fileEntryIds.isEmpty()) {
            return Map.of();
        }

        List<Integer> ids = fileEntryIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        QueryWrapper<FileEntry> wrapper = new QueryWrapper<FileEntry>()
                .eq("user_id", userId)
                .in("id", ids);
        return fileEntryMapper.selectList(wrapper).stream()
                .map(this::applyResourceURL)
                .collect(Collectors.toMap(FileEntry::getId, entry -> entry));
    }

    /**
     * 加载当前用户拥有的图片资源.
     * 只要fileEntry存在但不属于当前用户, 就抛出403异常;
     * 文件记录存在但磁盘文件已经缺失时, 则视为404.
     */
    public StoredResource loadOwnedImage(Integer fileEntryId, Integer userId) {
        FileEntry entry = getOwnedFileEntry(fileEntryId, userId);
        return loadStoredResource(entry);
    }

    /**
     * 加载当前用户"可访问"的图片资源.
     * <p>
     * 与loadOwnedImage的区别在于:
     * 1. 普通用户仍然只能访问自己的图片
     * 2. ADMIN角色可以访问任意用户图片
     * 3. ResourceController统一走这个入口, 避免Controller里散落角色分支
     */
    public StoredResource loadAccessibleImage(Integer fileEntryId, Integer userId, String currentRole) {
        FileEntry entry = getAccessibleFileEntry(fileEntryId, userId, currentRole);
        return loadStoredResource(entry);
    }

    /**
     * 基于文件元数据真正读取磁盘文件.
     * 这样权限判断和磁盘读取逻辑可以分层复用, 既方便普通用户读取, 也方便管理员读取.
     */
    private StoredResource loadStoredResource(FileEntry entry) {
        Path storedPath = resolveStoredPath(entry);
        if (!Files.isRegularFile(storedPath)) {
            throw new FileResourceNotFoundException("图片资源不存在");
        }

        try {
            long size = Files.size(storedPath);
            return new StoredResource(new FileSystemResource(storedPath), resolveMediaType(entry.getExtname()), size);
        } catch (IOException ex) {
            throw new IllegalStateException("读取图片文件失败", ex);
        }
    }

    /**
     * 删除当前用户拥有的文件及元数据.
     * 当前阶段没有暴露删除Controller, 但先把服务能力准备好, 方便后续做"删历史同时删文件".
     */
    public void deleteOwnedFile(Integer fileEntryId, Integer userId) {
        FileEntry entry = getOwnedFileEntry(fileEntryId, userId);
        deleteFileEntry(entry);
    }

    /**
     * 删除文件元数据及磁盘文件.
     * 先删数据库记录, 再删文件, 降低悬挂元数据风险.
     * <p>
     * 这里选择"先删库再删文件"的原因是:
     * 如果先删文件再删库, 一旦删库失败, 前端还能查到一条已经无法访问的记录.
     * 先删库可以优先保证业务侧不再暴露这条资源.
     */
    public void deleteFileEntry(FileEntry entry) {
        if (entry == null || entry.getId() == null) {
            return;
        }
        fileEntryMapper.deleteById(entry.getId());
        Path storedPath = resolveStoredPath(entry);
        try {
            Files.deleteIfExists(storedPath);
        } catch (IOException ex) {
            throw new IllegalStateException("删除图片文件失败", ex);
        }
    }

    /**
     * 构建资源相对路径.
     * 前端拿到这个相对路径后, 需要在已登录上下文中携带Authorization访问.
     */
    public String buildResourceURL(Integer fileEntryId) {
        return IMAGE_RESOURCE_PREFIX + fileEntryId;
    }

    /**
     * 读取单个文件元数据并做归属校验.
     * 不在这里直接返回文件流, 是为了把"权限判断"和"物理文件读取"拆开, 各自职责更清晰.
     */
    private FileEntry getOwnedFileEntry(Integer fileEntryId, Integer userId) {
        FileEntry entry = getExistingFileEntry(fileEntryId);
        if (!userId.equals(entry.getUserId())) {
            throw new FileAccessDeniedException("无权访问该图片");
        }
        return applyResourceURL(entry);
    }

    /**
     * 读取单个文件元数据并按"所有者或管理员"规则做访问判断.
     * <p>
     * 这是资源读取接口的新权限入口:
     * - 所有者始终允许访问
     * - ADMIN始终允许访问
     * - 其它角色访问他人文件时返回403
     */
    private FileEntry getAccessibleFileEntry(Integer fileEntryId, Integer userId, String currentRole) {
        validateUserId(userId);
        FileEntry entry = getExistingFileEntry(fileEntryId);
        if (!userId.equals(entry.getUserId()) && !UserRole.isAdmin(currentRole)) {
            throw new FileAccessDeniedException("无权访问该图片");
        }
        return applyResourceURL(entry);
    }

    /**
     * 读取文件元数据存在性.
     * 单独抽出来是为了让"所有者校验"和"管理员可访问校验"共用同一段存在性逻辑.
     */
    private FileEntry getExistingFileEntry(Integer fileEntryId) {
        FileEntry entry = fileEntryMapper.selectById(fileEntryId);
        if (entry == null) {
            throw new FileResourceNotFoundException("图片资源不存在");
        }
        return entry;
    }

    private FileEntry applyResourceURL(FileEntry entry) {
        if (entry != null && entry.getId() != null) {
            // resourceURL不入库, 每次查询时按统一规则动态拼接.
            entry.setResourceURL(buildResourceURL(entry.getId()));
        }
        return entry;
    }

    /**
     * 解析用户目录.
     * 所有用户图片都必须位于 storageRoot/image/{userId} 下.
     */
    private Path resolveUserDirectory(Integer userId) {
        return ensureWithinStorageRoot(storageRoot.resolve("image").resolve(String.valueOf(userId)));
    }

    /**
     * 解析最终文件路径.
     * 这里不会直接相信上游传入的directory字符串, 最终还要经过ensureWithinStorageRoot做兜底校验.
     */
    private Path resolveStoredPath(Path directory, String filename, String extname) {
        return ensureWithinStorageRoot(directory.resolve(filename + "." + extname));
    }

    /**
     * 从FileEntry还原出磁盘文件的完整路径.
     * file_entry表里保存的是父目录, UUID文件名和扩展名三段信息, 读取时再拼回去.
     */
    private Path resolveStoredPath(FileEntry entry) {
        Path directory = ensureWithinStorageRoot(Paths.get(entry.getDirectory()));
        return resolveStoredPath(directory, entry.getFilename(), entry.getExtname());
    }

    /**
     * 防止路径穿透:
     * 无论路径是运行时拼出来的, 还是从数据库读出来的, 都必须再次normalize并确认仍在storageRoot下.
     * 这样即使有人伪造了directory字段, 也无法访问到upload目录之外的文件.
     */
    private Path ensureWithinStorageRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(storageRoot)) {
            throw new IllegalStateException("文件路径超出存储根目录");
        }
        return normalized;
    }

    /**
     * 数据库落库失败时的文件回滚逻辑.
     * 如果回滚本身也失败, 就把清理异常挂到原异常上, 方便上层日志排查.
     */
    private void rollbackPhysicalFile(Path storedPath, RuntimeException cause) {
        try {
            Files.deleteIfExists(storedPath);
        } catch (IOException cleanupEx) {
            log.error("回滚已写入文件失败: {}", storedPath, cleanupEx);
            cause.addSuppressed(cleanupEx);
        }
    }

    private void validateUserId(Integer userId) {
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
    }

    /**
     * 从客户端文件名中提取"纯文件名".
     * 这里显式去掉可能出现的Windows/Unix路径前缀, 防止用户把完整本地路径带进后端.
     */
    private String extractClientFilename(String originalFilename) {
        String safeName = Optional.ofNullable(originalFilename)
                .map(String::trim)
                .orElse("")
                .replace("\\", "/");
        int lastSlash = safeName.lastIndexOf('/');
        String filename = lastSlash >= 0 ? safeName.substring(lastSlash + 1) : safeName;
        if (filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        return filename;
    }

    /**
     * 提取并校验扩展名.
     * 当前策略是"严格模式": 缺少扩展名, 点号位置异常, 或扩展名不在白名单内, 都直接拒绝.
     */
    private String extractExtname(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == filename.length() - 1) {
            throw new IllegalArgumentException("文件扩展名不合法");
        }
        String extname = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extname)) {
            throw new IllegalArgumentException("仅支持常见图片扩展名");
        }
        return extname;
    }

    /**
     * 根据扩展名推断HTTP响应的Content-Type.
     * 这里只影响前端怎么渲染文件, 不参与权限判断和磁盘定位.
     */
    private MediaType resolveMediaType(String extname) {
        return switch (extname) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "bmp" -> MediaType.parseMediaType("image/bmp");
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "tif", "tiff" -> MediaType.parseMediaType("image/tiff");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * 文件流读取结果.
     * 把Resource, Content-Type和文件长度打包返回给Controller, 避免Controller再次操作磁盘或做类型判断.
     */
    public record StoredResource(Resource resource, MediaType contentType, long size) {
    }
}
