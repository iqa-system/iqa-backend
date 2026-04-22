package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.data.dto.AdminAssessRecordView;
import org.wlow.iqabackend.data.dto.DistortionProb;
import org.wlow.iqabackend.data.dto.UserInfo;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.data.entity.PasswordResetCode;
import org.wlow.iqabackend.data.entity.User;
import org.wlow.iqabackend.exception.AssessRecordNotFoundException;
import org.wlow.iqabackend.exception.UserNotFoundException;
import org.wlow.iqabackend.mapper.AssessRecordMapper;
import org.wlow.iqabackend.mapper.FileEntryMapper;
import org.wlow.iqabackend.mapper.PasswordResetCodeMapper;
import org.wlow.iqabackend.mapper.UserMapper;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        CurrentUser.clearAll();
    }

    @Test
    void listUsersPreservesExtendedRoleAndSupportsEmptyFilters() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(1, "admin", "admin@example.com", UserRole.ADMIN.name()));
        userStore.seed(user(2, "expert", "expert@example.com", "EXPERT"));
        userStore.seed(user(3, "alice", "alice@example.com", UserRole.USER.name()));

        AdminService adminService = buildAdminService(userStore, new InMemoryAssessRecordStore(),
                new InMemoryPasswordResetCodeStore(), new InMemoryFileEntryStore());
        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());

        Page<UserInfo> allUsers = adminService.listUsers(1, 10, null, null, null);
        Page<UserInfo> expertUsers = adminService.listUsers(1, 10, null, null, "EXPERT");

        assertEquals(3, allUsers.getTotal());
        assertEquals("EXPERT", allUsers.getRecords().stream()
                .filter(user -> Objects.equals(user.getId(), 2))
                .findFirst()
                .map(UserInfo::getRole)
                .orElseThrow());
        assertEquals(1, expertUsers.getTotal());
        assertEquals(2, expertUsers.getRecords().get(0).getId());
    }

    @Test
    void updateUserRoleStoresCustomRoleStringAsIs() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(1, "admin", "admin@example.com", UserRole.ADMIN.name()));
        userStore.seed(user(2, "alice", "alice@example.com", UserRole.USER.name()));

        AdminService adminService = buildAdminService(userStore, new InMemoryAssessRecordStore(),
                new InMemoryPasswordResetCodeStore(), new InMemoryFileEntryStore());
        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());

        adminService.updateUserRole(2, "EXPERT_REVIEWER");

        assertEquals("EXPERT_REVIEWER", userStore.findById(2).getRole());
    }

    @Test
    void deleteUsersCascadesAssessHistoryPasswordResetAndFiles() throws Exception {
        InMemoryUserStore userStore = new InMemoryUserStore();
        InMemoryAssessRecordStore assessStore = new InMemoryAssessRecordStore();
        InMemoryPasswordResetCodeStore passwordResetCodeStore = new InMemoryPasswordResetCodeStore();
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();

        userStore.seed(user(1, "admin", "admin@example.com", UserRole.ADMIN.name()));
        userStore.seed(user(2, "alice", "alice@example.com", "EXPERT"));
        assessStore.seed(assessRecord(101, 2, "alice-noise.jpg", 301, LocalDateTime.of(2026, 4, 20, 10, 0)));
        passwordResetCodeStore.seed(passwordResetCode(401, 2, "alice@example.com"));

        Path imageDirectory = tempDir.resolve("image");
        Files.createDirectories(imageDirectory);
        Path storedPath = imageDirectory.resolve("uuid-a.jpg");
        Files.writeString(storedPath, "image-bytes");
        fileEntryStore.seed(fileEntry(301, 2, "uuid-a", "jpg", imageDirectory.toString(), 11L));

        AdminService adminService = buildAdminService(userStore, assessStore, passwordResetCodeStore, fileEntryStore);
        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());

        int deletedCount = adminService.deleteUsers(List.of(2));

        assertEquals(1, deletedCount);
        assertEquals(1, userStore.size());
        assertTrue(assessStore.records().isEmpty());
        assertTrue(passwordResetCodeStore.records().isEmpty());
        assertTrue(fileEntryStore.records().isEmpty());
        assertFalse(Files.exists(storedPath));
    }

    @Test
    void deleteUsersFailsAtomicallyWhenSomeUserDoesNotExist() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        InMemoryAssessRecordStore assessStore = new InMemoryAssessRecordStore();
        InMemoryPasswordResetCodeStore passwordResetCodeStore = new InMemoryPasswordResetCodeStore();
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();

        userStore.seed(user(1, "admin", "admin@example.com", UserRole.ADMIN.name()));
        userStore.seed(user(2, "alice", "alice@example.com", UserRole.USER.name()));

        AdminService adminService = buildAdminService(userStore, assessStore, passwordResetCodeStore, fileEntryStore);
        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());

        assertThrows(UserNotFoundException.class, () -> adminService.deleteUsers(List.of(2, 999)));
        assertEquals(2, userStore.size());
    }

    @Test
    void listAssessHistorySupportsOptionalFiltersAndBuildsImageUrl() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        InMemoryAssessRecordStore assessStore = new InMemoryAssessRecordStore();
        InMemoryPasswordResetCodeStore passwordResetCodeStore = new InMemoryPasswordResetCodeStore();
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();

        userStore.seed(user(1, "admin", "admin@example.com", UserRole.ADMIN.name()));
        userStore.seed(user(2, "alice", "alice@example.com", "EXPERT"));
        userStore.seed(user(3, "bob", "bob@example.com", UserRole.USER.name()));
        assessStore.seed(assessRecord(101, 2, "alice-noise.jpg", 301, LocalDateTime.of(2026, 4, 20, 10, 0)));
        assessStore.seed(assessRecord(102, 3, "bob-blur.jpg", 302, LocalDateTime.of(2026, 4, 21, 11, 0)));
        fileEntryStore.seed(fileEntry(301, 2, "uuid-a", "jpg", tempDir.resolve("image").toString(), 11L));
        fileEntryStore.seed(fileEntry(302, 3, "uuid-b", "png", tempDir.resolve("image").toString(), 12L));

        AdminService adminService = buildAdminService(userStore, assessStore, passwordResetCodeStore, fileEntryStore);
        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());

        Page<AdminAssessRecordView> result = adminService.listAssessHistory(
                1, 10, 2, "noise",
                LocalDateTime.of(2026, 4, 20, 0, 0),
                LocalDateTime.of(2026, 4, 20, 23, 59)
        );

        assertEquals(1, result.getTotal());
        assertEquals("alice", result.getRecords().get(0).getUsername());
        assertEquals("/resource/image/uuid-a.jpg", result.getRecords().get(0).getImageURL());
        assertEquals(new BigDecimal("4.30"), result.getRecords().get(0).getExpertScore());
        assertEquals(LocalDateTime.of(2026, 4, 20, 10, 5), result.getRecords().get(0).getExpertScoreTime());
    }

    @Test
    void deleteAssessHistoryRemovesMetadataAndFiles() throws Exception {
        InMemoryUserStore userStore = new InMemoryUserStore();
        InMemoryAssessRecordStore assessStore = new InMemoryAssessRecordStore();
        InMemoryPasswordResetCodeStore passwordResetCodeStore = new InMemoryPasswordResetCodeStore();
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();

        userStore.seed(user(1, "admin", "admin@example.com", UserRole.ADMIN.name()));
        assessStore.seed(assessRecord(101, 2, "alice-noise.jpg", 301, LocalDateTime.of(2026, 4, 20, 10, 0)));

        Path imageDirectory = tempDir.resolve("image");
        Files.createDirectories(imageDirectory);
        Path storedPath = imageDirectory.resolve("uuid-a.jpg");
        Files.writeString(storedPath, "image-bytes");
        fileEntryStore.seed(fileEntry(301, 2, "uuid-a", "jpg", imageDirectory.toString(), 11L));

        AdminService adminService = buildAdminService(userStore, assessStore, passwordResetCodeStore, fileEntryStore);
        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());

        int deletedCount = adminService.deleteAssessHistory(List.of(101));

        assertEquals(1, deletedCount);
        assertTrue(assessStore.records().isEmpty());
        assertTrue(fileEntryStore.records().isEmpty());
        assertFalse(Files.exists(storedPath));
    }

    @Test
    void deleteAssessHistoryFailsWhenAnyRecordIsMissing() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        InMemoryAssessRecordStore assessStore = new InMemoryAssessRecordStore();
        InMemoryPasswordResetCodeStore passwordResetCodeStore = new InMemoryPasswordResetCodeStore();
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();

        userStore.seed(user(1, "admin", "admin@example.com", UserRole.ADMIN.name()));
        assessStore.seed(assessRecord(101, 2, "alice-noise.jpg", 301, LocalDateTime.of(2026, 4, 20, 10, 0)));

        AdminService adminService = buildAdminService(userStore, assessStore, passwordResetCodeStore, fileEntryStore);
        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());

        assertThrows(AssessRecordNotFoundException.class, () -> adminService.deleteAssessHistory(List.of(101, 999)));
        assertEquals(1, assessStore.records().size());
    }

    private AdminService buildAdminService(InMemoryUserStore userStore,
                                           InMemoryAssessRecordStore assessStore,
                                           InMemoryPasswordResetCodeStore passwordResetCodeStore,
                                           InMemoryFileEntryStore fileEntryStore) {
        FileService fileService = new FileService(fileEntryStore.mapper(), tempDir.toString());
        return new AdminService(
                userStore.mapper(),
                assessStore.mapper(),
                passwordResetCodeStore.mapper(),
                fileEntryStore.mapper(),
                fileService
        );
    }

    private User user(Integer id, String username, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("$2a$10$dummy");
        user.setEmail(email);
        user.setRole(role);
        user.setRegisterTime(LocalDateTime.of(2026, 4, 8, 13, 0));
        return user;
    }

    private AssessRecord assessRecord(Integer id, Integer userId, String filename, Integer fileEntryId, LocalDateTime assessTime) {
        AssessRecord record = new AssessRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setFilename(filename);
        record.setFileEntryId(fileEntryId);
        record.setAssessTime(assessTime);
        record.setQualityScore(4.2D);
        record.setExpertScore(new BigDecimal("4.30"));
        record.setExpertScoreTime(assessTime.plusMinutes(5));
        record.setScene(3);
        record.setDistortions(List.of(new DistortionProb(1, 0.9)));
        return record;
    }

    private PasswordResetCode passwordResetCode(Integer id, Integer userId, String email) {
        PasswordResetCode code = new PasswordResetCode();
        code.setId(id);
        code.setUserId(userId);
        code.setEmail(email);
        code.setCreatedAt(LocalDateTime.of(2026, 4, 20, 9, 0));
        return code;
    }

    private FileEntry fileEntry(Integer id, Integer userId, String filename, String extname, String directory, Long size) {
        FileEntry entry = new FileEntry();
        entry.setId(id);
        entry.setUserId(userId);
        entry.setFilename(filename);
        entry.setExtname(extname);
        entry.setDirectory(directory);
        entry.setSize(size);
        entry.setUploadTime(LocalDateTime.of(2026, 4, 20, 10, 0));
        return entry;
    }

    private static final class InMemoryUserStore {
        private final Map<Integer, User> users = new LinkedHashMap<>();

        private void seed(User user) {
            users.put(user.getId(), cloneUser(user));
        }

        private int size() {
            return users.size();
        }

        private User findById(Integer id) {
            return cloneUser(users.get(id));
        }

        private UserMapper mapper() {
            return (UserMapper) Proxy.newProxyInstance(
                    UserMapper.class.getClassLoader(),
                    new Class<?>[]{UserMapper.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "InMemoryUserMapper";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        return switch (method.getName()) {
                            case "selectById" -> cloneUser(users.get((Integer) args[0]));
                            case "selectList" -> handleSelectList((QueryWrapper<User>) args[0]);
                            case "selectPage" -> handleSelectPage((Page<User>) args[0], (QueryWrapper<User>) args[1]);
                            case "updateById" -> handleUpdateById((User) args[0]);
                            case "deleteById" -> users.remove((Integer) args[0]) != null ? 1 : 0;
                            case "delete" -> handleDelete((QueryWrapper<User>) args[0]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private List<User> handleSelectList(QueryWrapper<User> wrapper) {
            String sqlSegment = wrapper.getSqlSegment();
            if (sqlSegment.contains("id IN")) {
                Set<Integer> ids = extractIntegerSet(wrapper.getParamNameValuePairs().values());
                return users.values().stream()
                        .filter(user -> ids.contains(user.getId()))
                        .map(this::cloneUser)
                        .toList();
            }
            throw new UnsupportedOperationException("unknown query: " + sqlSegment);
        }

        private Page<User> handleSelectPage(Page<User> page, QueryWrapper<User> wrapper) {
            String sqlSegment = wrapper.getSqlSegment();
            List<Object> values = new ArrayList<>(wrapper.getParamNameValuePairs().values());
            int index = 0;
            String username = sqlSegment.contains("username LIKE") ? (String) values.get(index++) : null;
            String email = sqlSegment.contains("email LIKE") ? (String) values.get(index++) : null;
            String role = sqlSegment.contains("role =") ? (String) values.get(index) : null;

            List<User> filtered = users.values().stream()
                    .filter(user -> username == null || user.getUsername().contains(normalizeLikeValue(username)))
                    .filter(user -> email == null || user.getEmail().contains(normalizeLikeValue(email)))
                    .filter(user -> role == null || Objects.equals(user.getRole(), role))
                    .sorted((left, right) -> right.getRegisterTime().compareTo(left.getRegisterTime()))
                    .map(this::cloneUser)
                    .toList();

            long startIndex = Math.max(0, (page.getCurrent() - 1) * page.getSize());
            long endIndex = Math.min(filtered.size(), startIndex + page.getSize());
            List<User> pageRecords = startIndex >= filtered.size()
                    ? List.of()
                    : filtered.subList((int) startIndex, (int) endIndex);

            page.setTotal(filtered.size());
            page.setRecords(pageRecords);
            return page;
        }

        private int handleUpdateById(User user) {
            users.put(user.getId(), cloneUser(user));
            return 1;
        }

        private int handleDelete(QueryWrapper<User> wrapper) {
            Set<Integer> ids = extractIntegerSet(wrapper.getParamNameValuePairs().values());
            int before = users.size();
            users.entrySet().removeIf(entry -> ids.contains(entry.getKey()));
            return before - users.size();
        }

        private User cloneUser(User user) {
            if (user == null) {
                return null;
            }
            User clone = new User();
            clone.setId(user.getId());
            clone.setUsername(user.getUsername());
            clone.setPassword(user.getPassword());
            clone.setEmail(user.getEmail());
            clone.setRole(user.getRole());
            clone.setRegisterTime(user.getRegisterTime());
            return clone;
        }
    }

    private static final class InMemoryAssessRecordStore {
        private final Map<Integer, AssessRecord> records = new LinkedHashMap<>();

        private void seed(AssessRecord record) {
            records.put(record.getId(), cloneRecord(record));
        }

        private List<AssessRecord> records() {
            return records.values().stream().map(this::cloneRecord).toList();
        }

        private AssessRecordMapper mapper() {
            return (AssessRecordMapper) Proxy.newProxyInstance(
                    AssessRecordMapper.class.getClassLoader(),
                    new Class<?>[]{AssessRecordMapper.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "InMemoryAssessRecordMapper";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        return switch (method.getName()) {
                            case "selectByIdList" -> handleSelectByIdList((List<Integer>) args[0]);
                            case "selectByUserIdList" -> handleSelectByUserIdList((List<Integer>) args[0]);
                            case "selectPage" -> handleSelectPage((Page<AssessRecord>) args[0], (QueryWrapper<AssessRecord>) args[1]);
                            case "delete" -> handleDelete((QueryWrapper<AssessRecord>) args[0]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private List<AssessRecord> handleSelectByIdList(List<Integer> ids) {
            return ids.stream()
                    .map(records::get)
                    .filter(Objects::nonNull)
                    .map(this::cloneRecord)
                    .toList();
        }

        private List<AssessRecord> handleSelectByUserIdList(List<Integer> userIds) {
            Set<Integer> targets = new LinkedHashSet<>(userIds);
            return records.values().stream()
                    .filter(record -> targets.contains(record.getUserId()))
                    .map(this::cloneRecord)
                    .toList();
        }

        private Page<AssessRecord> handleSelectPage(Page<AssessRecord> page, QueryWrapper<AssessRecord> wrapper) {
            String sqlSegment = wrapper.getSqlSegment();
            List<Object> values = new ArrayList<>(wrapper.getParamNameValuePairs().values());
            Integer userId = sqlSegment.contains("user_id =")
                    ? values.stream().filter(Integer.class::isInstance).map(Integer.class::cast).findFirst().orElse(null)
                    : null;
            String filename = sqlSegment.contains("filename LIKE")
                    ? values.stream().filter(String.class::isInstance).map(String.class::cast).findFirst().orElse(null)
                    : null;
            List<LocalDateTime> times = values.stream()
                    .filter(LocalDateTime.class::isInstance)
                    .map(LocalDateTime.class::cast)
                    .toList();
            LocalDateTime startTime = sqlSegment.contains("assess_time >=") && !times.isEmpty() ? times.get(0) : null;
            LocalDateTime endTime = sqlSegment.contains("assess_time <=") && times.size() >= 2
                    ? times.get(1)
                    : (sqlSegment.contains("assess_time <=") && times.size() == 1 ? times.get(0) : null);

            List<AssessRecord> filtered = records.values().stream()
                    .filter(record -> userId == null || Objects.equals(record.getUserId(), userId))
                    .filter(record -> filename == null || record.getFilename().contains(normalizeLikeValue(filename)))
                    .filter(record -> startTime == null || !record.getAssessTime().isBefore(startTime))
                    .filter(record -> endTime == null || !record.getAssessTime().isAfter(endTime))
                    .sorted((left, right) -> right.getAssessTime().compareTo(left.getAssessTime()))
                    .map(this::cloneRecord)
                    .toList();

            long startIndex = Math.max(0, (page.getCurrent() - 1) * page.getSize());
            long endIndex = Math.min(filtered.size(), startIndex + page.getSize());
            List<AssessRecord> pageRecords = startIndex >= filtered.size()
                    ? List.of()
                    : filtered.subList((int) startIndex, (int) endIndex);

            page.setTotal(filtered.size());
            page.setRecords(pageRecords);
            return page;
        }

        private int handleDelete(QueryWrapper<AssessRecord> wrapper) {
            String sqlSegment = wrapper.getSqlSegment();
            int before = records.size();
            if (sqlSegment.contains("user_id IN")) {
                Set<Integer> userIds = extractIntegerSet(wrapper.getParamNameValuePairs().values());
                records.entrySet().removeIf(entry -> userIds.contains(entry.getValue().getUserId()));
            } else if (sqlSegment.contains("id IN")) {
                Set<Integer> ids = extractIntegerSet(wrapper.getParamNameValuePairs().values());
                records.entrySet().removeIf(entry -> ids.contains(entry.getKey()));
            } else {
                throw new UnsupportedOperationException("unknown delete: " + sqlSegment);
            }
            return before - records.size();
        }

        private AssessRecord cloneRecord(AssessRecord record) {
            if (record == null) {
                return null;
            }
            AssessRecord clone = new AssessRecord();
            clone.setId(record.getId());
            clone.setUserId(record.getUserId());
            clone.setFilename(record.getFilename());
            clone.setFileEntryId(record.getFileEntryId());
            clone.setImageURL(record.getImageURL());
            clone.setAssessTime(record.getAssessTime());
            clone.setQualityScore(record.getQualityScore());
            clone.setExpertScore(record.getExpertScore());
            clone.setExpertScoreTime(record.getExpertScoreTime());
            clone.setScene(record.getScene());
            clone.setDistortions(record.getDistortions());
            return clone;
        }
    }

    private static final class InMemoryPasswordResetCodeStore {
        private final Map<Integer, PasswordResetCode> codes = new LinkedHashMap<>();

        private void seed(PasswordResetCode code) {
            codes.put(code.getId(), cloneCode(code));
        }

        private List<PasswordResetCode> records() {
            return codes.values().stream().map(this::cloneCode).toList();
        }

        private PasswordResetCodeMapper mapper() {
            return (PasswordResetCodeMapper) Proxy.newProxyInstance(
                    PasswordResetCodeMapper.class.getClassLoader(),
                    new Class<?>[]{PasswordResetCodeMapper.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "InMemoryPasswordResetCodeMapper";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        return switch (method.getName()) {
                            case "delete" -> handleDelete((QueryWrapper<PasswordResetCode>) args[0]);
                            case "deleteByUserIdList" -> handleDeleteByUserIdList((List<Integer>) args[0]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private int handleDeleteByUserIdList(List<Integer> userIds) {
            Set<Integer> ids = new LinkedHashSet<>(userIds);
            int before = codes.size();
            codes.entrySet().removeIf(entry -> ids.contains(entry.getValue().getUserId()));
            return before - codes.size();
        }

        private int handleDelete(QueryWrapper<PasswordResetCode> wrapper) {
            Set<Integer> userIds = extractIntegerSet(wrapper.getParamNameValuePairs().values());
            int before = codes.size();
            codes.entrySet().removeIf(entry -> userIds.contains(entry.getValue().getUserId()));
            return before - codes.size();
        }

        private PasswordResetCode cloneCode(PasswordResetCode code) {
            if (code == null) {
                return null;
            }
            PasswordResetCode clone = new PasswordResetCode();
            clone.setId(code.getId());
            clone.setUserId(code.getUserId());
            clone.setEmail(code.getEmail());
            clone.setCodeHash(code.getCodeHash());
            clone.setExpiresAt(code.getExpiresAt());
            clone.setAttemptCount(code.getAttemptCount());
            clone.setConsumedAt(code.getConsumedAt());
            clone.setCreatedAt(code.getCreatedAt());
            return clone;
        }
    }

    private static final class InMemoryFileEntryStore {
        private final Map<Integer, FileEntry> fileEntries = new LinkedHashMap<>();

        private void seed(FileEntry entry) {
            fileEntries.put(entry.getId(), cloneEntry(entry));
        }

        private List<FileEntry> records() {
            return fileEntries.values().stream().map(this::cloneEntry).toList();
        }

        private FileEntryMapper mapper() {
            return (FileEntryMapper) Proxy.newProxyInstance(
                    FileEntryMapper.class.getClassLoader(),
                    new Class<?>[]{FileEntryMapper.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "InMemoryFileEntryMapper";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        return switch (method.getName()) {
                            case "selectList" -> handleSelectList((QueryWrapper<FileEntry>) args[0]);
                            case "selectById" -> cloneEntry(fileEntries.get((Integer) args[0]));
                            case "deleteById" -> fileEntries.remove((Integer) args[0]) != null ? 1 : 0;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private List<FileEntry> handleSelectList(QueryWrapper<FileEntry> wrapper) {
            String sqlSegment = wrapper.getSqlSegment();
            Set<Integer> values = extractIntegerSet(wrapper.getParamNameValuePairs().values());

            if (sqlSegment.contains("user_id IN")) {
                return fileEntries.values().stream()
                        .filter(entry -> values.contains(entry.getUserId()))
                        .map(this::cloneEntry)
                        .toList();
            }
            if (sqlSegment.contains("id IN")) {
                return fileEntries.values().stream()
                        .filter(entry -> values.contains(entry.getId()))
                        .map(this::cloneEntry)
                        .toList();
            }
            throw new UnsupportedOperationException("unknown query: " + sqlSegment);
        }

        private FileEntry cloneEntry(FileEntry entry) {
            if (entry == null) {
                return null;
            }
            FileEntry clone = new FileEntry();
            clone.setId(entry.getId());
            clone.setUserId(entry.getUserId());
            clone.setFilename(entry.getFilename());
            clone.setExtname(entry.getExtname());
            clone.setDirectory(entry.getDirectory());
            clone.setSize(entry.getSize());
            clone.setUploadTime(entry.getUploadTime());
            clone.setResourceURL(entry.getResourceURL());
            return clone;
        }
    }

    private static Set<Integer> extractIntegerSet(Iterable<?> values) {
        Set<Integer> result = new LinkedHashSet<>();
        for (Object value : values) {
            collectIntegerValues(value, result);
        }
        return result;
    }

    private static void collectIntegerValues(Object value, Set<Integer> target) {
        if (value == null) {
            return;
        }
        if (value instanceof Integer intValue) {
            target.add(intValue);
            return;
        }
        if (value instanceof Number numberValue) {
            target.add(numberValue.intValue());
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectIntegerValues(item, target);
            }
        }
    }

    private static String normalizeLikeValue(String value) {
        return value == null ? null : value.replace("%", "");
    }
}
