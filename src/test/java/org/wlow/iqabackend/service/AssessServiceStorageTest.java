package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.dto.PredictResult;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.mapper.AssessRecordMapper;
import org.wlow.iqabackend.mapper.FileEntryMapper;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssessServiceStorageTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        CurrentUser.clearAll();
    }

    @Test
    void historyFillsImageUrlByBatchLookup() {
        FileEntry fileEntry = new FileEntry();
        fileEntry.setId(21);
        fileEntry.setUserId(7);
        fileEntry.setResourceURL("/resource/image/21");

        TestFileService fileService = new TestFileService(tempDir, Map.of(21, fileEntry), null);
        AssessRecord record = new AssessRecord();
        record.setId(1);
        record.setUserId(7);
        record.setFilename("sample.jpg");
        record.setFileEntryId(21);
        record.setAssessTime(LocalDateTime.of(2026, 4, 7, 12, 0, 0));

        AssessService service = new AssessService(null, mapperForPage(List.of(record), null), fileService);
        CurrentUser.setAll(7, "alice");

        Page<AssessRecord> result = service.history(1, 10);

        assertEquals("/resource/image/21", result.getRecords().get(0).getImageURL());
    }

    @Test
    void saveAssessRecordStoresFileEntryIdAndImageUrl() {
        FileEntry uploadedEntry = new FileEntry();
        uploadedEntry.setId(31);
        uploadedEntry.setUserId(7);
        uploadedEntry.setResourceURL("/resource/image/31");

        TestFileService fileService = new TestFileService(tempDir, Map.of(), uploadedEntry);
        Map<String, AssessRecord> inserted = new LinkedHashMap<>();
        AssessService service = new AssessService(null, mapperForPage(List.of(), inserted), fileService);

        PredictResult result = new PredictResult();
        result.setQualityScore(0.91f);
        result.setScene(2);

        service.saveAssessRecord(new byte[]{1, 2}, "origin.png", 7, result);

        AssessRecord savedRecord = inserted.get("record");
        assertEquals("origin.png", savedRecord.getFilename());
        assertEquals(31, savedRecord.getFileEntryId());
        assertEquals("/resource/image/31", savedRecord.getImageURL());
        assertEquals(0.91d, savedRecord.getQualityScore(), 0.000001d);
    }

    @Test
    void saveAssessRecordRollsBackFileEntryWhenRecordInsertFails() {
        FileEntry uploadedEntry = new FileEntry();
        uploadedEntry.setId(41);
        uploadedEntry.setUserId(7);
        uploadedEntry.setResourceURL("/resource/image/41");

        TestFileService fileService = new TestFileService(tempDir, Map.of(), uploadedEntry);
        RuntimeException failure = new RuntimeException("insert assess record failed");
        AssessService service = new AssessService(null, mapperForPage(List.of(), failure), fileService);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.saveAssessRecord(new byte[]{1}, "failed.jpg", 7, new PredictResult()));

        assertSame(failure, exception);
        assertEquals(1, fileService.deletedEntries().size());
        assertEquals(41, fileService.deletedEntries().get(0).getId());
    }

    private AssessRecordMapper mapperForPage(List<AssessRecord> records, Object insertBehavior) {
        return (AssessRecordMapper) Proxy.newProxyInstance(
                AssessRecordMapper.class.getClassLoader(),
                new Class<?>[]{AssessRecordMapper.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "AssessRecordMapperProxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return switch (method.getName()) {
                        case "selectPage" -> handleSelectPage((Page<AssessRecord>) args[0], records);
                        case "insert" -> handleInsert((AssessRecord) args[0], insertBehavior);
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private Page<AssessRecord> handleSelectPage(Page<AssessRecord> page, List<AssessRecord> records) {
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    private int handleInsert(AssessRecord record, Object insertBehavior) {
        if (insertBehavior instanceof RuntimeException exception) {
            throw exception;
        }
        if (insertBehavior instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, AssessRecord> target = (Map<String, AssessRecord>) map;
            target.put("record", record);
        }
        return 1;
    }

    private static final class TestFileService extends FileService {
        private final Map<Integer, FileEntry> ownedEntries;
        private final FileEntry uploadedEntry;
        private final List<FileEntry> deletedEntries = new java.util.ArrayList<>();

        private TestFileService(Path tempDir, Map<Integer, FileEntry> ownedEntries, FileEntry uploadedEntry) {
            super(dummyFileEntryMapper(), tempDir.toString());
            this.ownedEntries = ownedEntries;
            this.uploadedEntry = uploadedEntry;
        }

        @Override
        public FileEntry upload(byte[] bytes, String originalFilename, Integer userId) {
            return uploadedEntry;
        }

        @Override
        public Map<Integer, FileEntry> mapOwnedFileEntriesByIds(Integer userId, java.util.Collection<Integer> fileEntryIds) {
            return ownedEntries;
        }

        @Override
        public void deleteFileEntry(FileEntry entry) {
            deletedEntries.add(entry);
        }

        private List<FileEntry> deletedEntries() {
            return deletedEntries;
        }
    }

    private static FileEntryMapper dummyFileEntryMapper() {
        return (FileEntryMapper) Proxy.newProxyInstance(
                FileEntryMapper.class.getClassLoader(),
                new Class<?>[]{FileEntryMapper.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "DummyFileEntryMapper";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
