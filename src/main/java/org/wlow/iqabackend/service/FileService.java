package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.wlow.iqabackend.data.entity.FileEntry;
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
 * 4. 对外暴露的是公开静态资源地址/resource/image/{uuid}.{ext}, 不再走按用户鉴权的业务读取接口.
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

        // 新版本改成扁平目录存储, 目录固定为 <root>/image.
        // 这样resource handler可以直接把/resource/image/** 映射到该目录, 前端能直接通过静态URL访问图片.
        Path imageDirectory = resolveImageDirectory();
        Path storedPath = resolveStoredPath(imageDirectory, uuidFilename, extname);

        try {
            // 目录不存在时自动创建; 已存在时不会报错.
            Files.createDirectories(imageDirectory);
            Files.write(storedPath, bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("保存图片文件失败", ex);
        }

        FileEntry entry = new FileEntry();
        entry.setUserId(userId);
        entry.setFilename(uuidFilename);
        entry.setExtname(extname);
        entry.setDirectory(imageDirectory.toString());
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
     * 按主键批量查询文件元数据, 并回填资源URL.
     * 这里不再按用户过滤, 因为图片URL已经改成公开静态资源, 历史列表只需要拿到file_entry对应的静态地址即可.
     */
    public Map<Integer, FileEntry> mapFileEntriesByIds(Collection<Integer> fileEntryIds) {
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
                .in("id", ids);
        return fileEntryMapper.selectList(wrapper).stream()
                .map(this::applyResourceURL)
                .collect(Collectors.toMap(FileEntry::getId, entry -> entry));
    }

    /**
     * 删除当前用户拥有的文件及元数据.
     * 当前阶段没有暴露删除Controller, 但先把服务能力准备好, 方便后续做"删历史同时删文件".
     */
    public void deleteOwnedFile(Integer fileEntryId, Integer userId) {
        validateUserId(userId);
        FileEntry entry = getExistingFileEntry(fileEntryId);
        if (!userId.equals(entry.getUserId())) {
            throw new IllegalArgumentException("无权删除该图片");
        }
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
     * 静态资源模式下, 图片URL直接由UUID文件名和扩展名组成, 前端可以直接赋给<img>标签使用.
     */
    public String buildResourceURL(String filename, String extname) {
        return IMAGE_RESOURCE_PREFIX + filename + "." + extname;
    }

    /**
     * 读取文件元数据存在性.
     * 删除链路仍然需要根据fileEntryId找到元数据, 然后再还原磁盘路径.
     */
    private FileEntry getExistingFileEntry(Integer fileEntryId) {
        return Optional.ofNullable(fileEntryMapper.selectById(fileEntryId))
                .orElseThrow(() -> new IllegalArgumentException("图片资源不存在"));
    }

    private FileEntry applyResourceURL(FileEntry entry) {
        if (entry != null && entry.getFilename() != null && entry.getExtname() != null) {
            // resourceURL不入库, 每次查询时按统一规则动态拼接.
            entry.setResourceURL(buildResourceURL(entry.getFilename(), entry.getExtname()));
        }
        return entry;
    }

    /**
     * 解析统一图片目录.
     * 静态资源方案下, 所有新图片都落在 storageRoot/image 下, 不再按用户分子目录.
     */
    public Path resolveImageDirectory() {
        return ensureWithinStorageRoot(storageRoot.resolve("image"));
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

}
