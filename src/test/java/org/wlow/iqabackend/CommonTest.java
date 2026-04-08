package org.wlow.iqabackend;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.mapper.AssessRecordMapper;
import org.wlow.iqabackend.mapper.FileEntryMapper;
import org.wlow.iqabackend.service.AssessService;
import org.wlow.iqabackend.service.FileService;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommonTest {
    @TempDir
    Path tempDir;

    @Test
    void test() {
        // 测试python服务的/health端点是否可达
        // 用RestClient直接调用http://localhost:8000/health
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:8000")
                .build();
        String response = restClient.get()
                .uri("/health")
                .retrieve()
                .body(String.class);
        System.out.println("检测python模型服务: " + response);
    }

    @Test
    void deleteAllHistoryByUserIdShouldRemoveRecordsFilesAndFileEntries() {
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();
        FileService fileService = new FileService(fileEntryStore.mapper(), tempDir.toString());

        FileEntry userFile1 = fileService.upload(new byte[]{1, 2, 3}, "first.jpg", 7);
        FileEntry userFile2 = fileService.upload(new byte[]{4, 5, 6}, "second.png", 7);
        FileEntry otherUserFile = fileService.upload(new byte[]{7, 8, 9}, "other.jpg", 8);

        Path userPath1 = buildStoredPath(userFile1);
        Path userPath2 = buildStoredPath(userFile2);
        Path otherUserPath = buildStoredPath(otherUserFile);

        InMemoryAssessRecordStore assessRecordStore = new InMemoryAssessRecordStore(List.of(
                buildRecord(1, 7, "first.jpg", userFile1.getId()),
                buildRecord(2, 7, "second.png", userFile2.getId()),
                buildRecord(3, 8, "other.jpg", otherUserFile.getId())
        ));
        AssessService assessService = new AssessService(null, assessRecordStore.mapper(), fileService);

        int deleted = assessService.deleteAllHistoryByUserId(7);

        assertEquals(2, deleted);
        assertTrue(assessRecordStore.findByUserId(7).isEmpty());
        assertEquals(1, assessRecordStore.findByUserId(8).size());

        assertFalse(Files.exists(userPath1));
        assertFalse(Files.exists(userPath2));
        assertTrue(Files.exists(otherUserPath));

        assertFalse(fileEntryStore.exists(userFile1.getId()));
        assertFalse(fileEntryStore.exists(userFile2.getId()));
        assertTrue(fileEntryStore.exists(otherUserFile.getId()));
    }

    private Path buildStoredPath(FileEntry entry) {
        return Path.of(entry.getDirectory(), entry.getFilename() + "." + entry.getExtname());
    }

    private AssessRecord buildRecord(int id, int userId, String filename, int fileEntryId) {
        AssessRecord record = new AssessRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setFilename(filename);
        record.setFileEntryId(fileEntryId);
        record.setAssessTime(LocalDateTime.now());
        return record;
    }

    private static final class InMemoryAssessRecordStore {
        private final Map<Integer, AssessRecord> records = new LinkedHashMap<>();

        private InMemoryAssessRecordStore(List<AssessRecord> seedRecords) {
            for (AssessRecord record : seedRecords) {
                records.put(record.getId(), cloneRecord(record));
            }
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
                            case "selectFileEntryIdsByUserId" -> handleSelectFileEntryIdsByUserId((Integer) args[0]);
                            case "deleteByUserId" -> handleDeleteByUserId((Integer) args[0]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private List<AssessRecord> findByUserId(Integer userId) {
            return records.values().stream()
                    .filter(record -> userId.equals(record.getUserId()))
                    .map(this::cloneRecord)
                    .toList();
        }

        private List<Integer> handleSelectFileEntryIdsByUserId(Integer userId) {
            return findByUserId(userId).stream()
                    .map(AssessRecord::getFileEntryId)
                    .toList();
        }

        private int handleDeleteByUserId(Integer userId) {
            List<Integer> idsToDelete = records.values().stream()
                    .filter(record -> userId.equals(record.getUserId()))
                    .map(AssessRecord::getId)
                    .toList();
            idsToDelete.forEach(records::remove);
            return idsToDelete.size();
        }

        private AssessRecord cloneRecord(AssessRecord record) {
            AssessRecord clone = new AssessRecord();
            clone.setId(record.getId());
            clone.setUserId(record.getUserId());
            clone.setFilename(record.getFilename());
            clone.setFileEntryId(record.getFileEntryId());
            clone.setImageURL(record.getImageURL());
            clone.setAssessTime(record.getAssessTime());
            clone.setQualityScore(record.getQualityScore());
            clone.setScene(record.getScene());
            clone.setDistortions(record.getDistortions());
            return clone;
        }
    }

    private static final class InMemoryFileEntryStore {
        private final Map<Integer, FileEntry> entries = new LinkedHashMap<>();
        private int nextId = 1;

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
                            case "insert" -> handleInsert((FileEntry) args[0]);
                            case "selectById" -> handleSelectById((Integer) args[0]);
                            case "deleteById" -> entries.remove((Integer) args[0]) != null ? 1 : 0;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private boolean exists(Integer id) {
            return entries.containsKey(id);
        }

        private int handleInsert(FileEntry entry) {
            entry.setId(nextId++);
            entries.put(entry.getId(), cloneEntry(entry));
            return 1;
        }

        private FileEntry handleSelectById(Integer id) {
            FileEntry entry = entries.get(id);
            return entry == null ? null : cloneEntry(entry);
        }

        private FileEntry cloneEntry(FileEntry entry) {
            return new FileEntry(
                    entry.getId(),
                    entry.getUserId(),
                    entry.getFilename(),
                    entry.getExtname(),
                    entry.getDirectory(),
                    entry.getSize(),
                    entry.getUploadTime(),
                    entry.getResourceURL()
            );
        }
    }
}
