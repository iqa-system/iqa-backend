package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClient;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.data.dto.DistortionProb;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.exception.AssessRecordAccessDeniedException;
import org.wlow.iqabackend.exception.ExpertAccessDeniedException;
import org.wlow.iqabackend.mapper.AssessRecordMapper;
import org.wlow.iqabackend.mapper.FileEntryMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssessServiceExpertScoreTest {

    @TempDir
    Path tempDir;

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        CurrentUser.clearAll();
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void assessPersistsExpertScoreForExpertRole() throws Exception {
        AssessServiceFixture fixture = buildFixture();
        CurrentUser.setAll(7, "expert", UserRole.EXPERT.name());

        fixture.assessService().assess(mockFile("sample.jpg"), "4.25");

        AssessRecord record = fixture.assessStore().records().get(0);
        assertEquals(new BigDecimal("4.25"), record.getExpertScore());
        assertNotNull(record.getExpertScoreTime());
        assertEquals(4.3D, record.getQualityScore(), 0.000001D);
    }

    @Test
    void assessRejectsExpertScoreForNonExpertRole() throws Exception {
        AssessServiceFixture fixture = buildFixture();
        CurrentUser.setAll(8, "alice", UserRole.USER.name());

        assertThrows(ExpertAccessDeniedException.class,
                () -> fixture.assessService().assess(mockFile("sample.jpg"), "4.25"));
        assertTrue(fixture.assessStore().records().isEmpty());
    }

    @Test
    void assessBatchPersistsPerFileExpertScores() throws Exception {
        AssessServiceFixture fixture = buildFixture();
        CurrentUser.setAll(9, "expert", UserRole.EXPERT.name());

        fixture.assessService().assessBatchStream(
                List.of(mockFile("a.jpg"), mockFile("b.jpg")),
                List.of("4.10", "")
        );

        waitUntilRecords(fixture.assessStore(), 2);
        List<AssessRecord> records = fixture.assessStore().records();

        assertEquals(new BigDecimal("4.10"), records.get(0).getExpertScore());
        assertNull(records.get(1).getExpertScore());
        assertNotNull(records.get(0).getExpertScoreTime());
        assertNull(records.get(1).getExpertScoreTime());
    }

    @Test
    void assessBatchRejectsScoreCountMismatch() throws Exception {
        AssessServiceFixture fixture = buildFixture();
        CurrentUser.setAll(9, "expert", UserRole.EXPERT.name());

        assertThrows(IllegalArgumentException.class, () -> fixture.assessService().assessBatchStream(
                List.of(mockFile("a.jpg"), mockFile("b.jpg")),
                List.of("4.10")
        ));
    }

    @Test
    void updateExpertScoreAllowsExpertToModifyOwnHistory() throws Exception {
        AssessServiceFixture fixture = buildFixture();
        CurrentUser.setAll(11, "expert", UserRole.EXPERT.name());

        AssessRecord record = new AssessRecord();
        record.setId(101);
        record.setUserId(11);
        record.setFilename("sample.jpg");
        record.setFileEntryId(201);
        record.setAssessTime(LocalDateTime.of(2026, 4, 22, 10, 0));
        record.setQualityScore(4.3D);
        record.setScene(2);
        record.setDistortions(List.of(new DistortionProb(1, 0.9)));
        fixture.assessStore().seed(record);

        fixture.assessService().updateExpertScore(101, "4.50");

        AssessRecord updated = fixture.assessStore().findById(101);
        assertEquals(new BigDecimal("4.50"), updated.getExpertScore());
        assertNotNull(updated.getExpertScoreTime());
    }

    @Test
    void updateExpertScoreRejectsOtherUsersHistory() throws Exception {
        AssessServiceFixture fixture = buildFixture();
        CurrentUser.setAll(11, "expert", UserRole.EXPERT.name());

        AssessRecord record = new AssessRecord();
        record.setId(101);
        record.setUserId(12);
        record.setFilename("sample.jpg");
        record.setFileEntryId(201);
        record.setAssessTime(LocalDateTime.of(2026, 4, 22, 10, 0));
        record.setQualityScore(4.3D);
        record.setScene(2);
        record.setDistortions(List.of(new DistortionProb(1, 0.9)));
        fixture.assessStore().seed(record);

        assertThrows(AssessRecordAccessDeniedException.class,
                () -> fixture.assessService().updateExpertScore(101, "4.50"));
    }

    private AssessServiceFixture buildFixture() throws Exception {
        InMemoryAssessRecordStore assessStore = new InMemoryAssessRecordStore();
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();
        FileService fileService = new FileService(fileEntryStore.mapper(), tempDir.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/predict", exchange -> writeJson(exchange, """
                {
                  "file_name": "sample.jpg",
                  "model_id": "liqe_default",
                  "quality_score": 4.3,
                  "extra_outputs": {},
                  "scene": 2,
                  "distortions": [
                    { "index": 1, "prob": 0.91 }
                  ]
                }
                """));
        server.createContext("/predict/batch", exchange -> writeJson(exchange, """
                {
                  "results": [
                    {
                      "file_name": "a.jpg",
                      "model_id": "liqe_default",
                      "quality_score": 4.1,
                      "extra_outputs": {},
                      "scene": 1,
                      "distortions": [
                        { "index": 1, "prob": 0.80 }
                      ]
                    },
                    {
                      "file_name": "b.jpg",
                      "model_id": "liqe_default",
                      "quality_score": 3.8,
                      "extra_outputs": {},
                      "scene": 2,
                      "distortions": [
                        { "index": 2, "prob": 0.70 }
                      ]
                    }
                  ]
                }
                """));
        server.start();
        this.httpServer = server;

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build();
        AssessService assessService = new AssessService(restClient, assessStore.mapper(), fileService);
        return new AssessServiceFixture(assessService, assessStore);
    }

    private MockMultipartFile mockFile(String filename) {
        return new MockMultipartFile("file", filename, "image/jpeg", "image-bytes".getBytes(StandardCharsets.UTF_8));
    }

    private void waitUntilRecords(InMemoryAssessRecordStore assessStore, int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (assessStore.records().size() >= expectedCount) {
                return;
            }
            Thread.sleep(50L);
        }
        assertEquals(expectedCount, assessStore.records().size());
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private record AssessServiceFixture(AssessService assessService, InMemoryAssessRecordStore assessStore) {
    }

    private static final class InMemoryAssessRecordStore {
        private final Map<Integer, AssessRecord> records = new LinkedHashMap<>();
        private int nextId = 1;

        private void seed(AssessRecord record) {
            records.put(record.getId(), cloneRecord(record));
            nextId = Math.max(nextId, record.getId() + 1);
        }

        private AssessRecord findById(Integer id) {
            return cloneRecord(records.get(id));
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
                            case "insert" -> handleInsert((AssessRecord) args[0]);
                            case "selectById" -> cloneRecord(records.get((Integer) args[0]));
                            case "updateById" -> handleUpdateById((AssessRecord) args[0]);
                            case "selectPage" -> handleSelectPage((Page<AssessRecord>) args[0], (QueryWrapper<AssessRecord>) args[1]);
                            case "selectByIdList" -> handleSelectByIdList((List<Integer>) args[0]);
                            case "deleteByUserIdAndIds" -> handleDeleteByUserIdAndIds((Integer) args[0], (List<Integer>) args[1]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private int handleInsert(AssessRecord record) {
            AssessRecord stored = cloneRecord(record);
            stored.setId(nextId++);
            records.put(stored.getId(), stored);
            record.setId(stored.getId());
            return 1;
        }

        private int handleUpdateById(AssessRecord record) {
            if (!records.containsKey(record.getId())) {
                return 0;
            }
            records.put(record.getId(), cloneRecord(record));
            return 1;
        }

        private Page<AssessRecord> handleSelectPage(Page<AssessRecord> page, QueryWrapper<AssessRecord> wrapper) {
            String sqlSegment = wrapper.getSqlSegment();
            Integer userId = sqlSegment.contains("user_id =")
                    ? wrapper.getParamNameValuePairs().values().stream()
                    .filter(Integer.class::isInstance)
                    .map(Integer.class::cast)
                    .findFirst()
                    .orElse(null)
                    : null;

            List<AssessRecord> filtered = records.values().stream()
                    .filter(record -> userId == null || Objects.equals(record.getUserId(), userId))
                    .sorted((left, right) -> right.getAssessTime().compareTo(left.getAssessTime()))
                    .map(this::cloneRecord)
                    .toList();
            page.setTotal(filtered.size());
            page.setRecords(filtered);
            return page;
        }

        private List<AssessRecord> handleSelectByIdList(List<Integer> ids) {
            return ids.stream()
                    .map(records::get)
                    .filter(Objects::nonNull)
                    .map(this::cloneRecord)
                    .toList();
        }

        private int handleDeleteByUserIdAndIds(Integer userId, List<Integer> ids) {
            Set<Integer> targets = new LinkedHashSet<>(ids);
            int before = records.size();
            records.entrySet().removeIf(entry -> userId.equals(entry.getValue().getUserId()) && targets.contains(entry.getKey()));
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
            clone.setModelId(record.getModelId());
            clone.setExpertScore(record.getExpertScore());
            clone.setExpertScoreTime(record.getExpertScoreTime());
            clone.setScene(record.getScene());
            clone.setDistortions(record.getDistortions());
            return clone;
        }
    }

    private static final class InMemoryFileEntryStore {
        private final Map<Integer, FileEntry> fileEntries = new LinkedHashMap<>();
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
                            case "selectList" -> handleSelectList((QueryWrapper<FileEntry>) args[0]);
                            case "selectById" -> cloneEntry(fileEntries.get((Integer) args[0]));
                            case "deleteById" -> fileEntries.remove((Integer) args[0]) != null ? 1 : 0;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private int handleInsert(FileEntry entry) {
            FileEntry stored = cloneEntry(entry);
            stored.setId(nextId++);
            fileEntries.put(stored.getId(), stored);
            entry.setId(stored.getId());
            return 1;
        }

        private List<FileEntry> handleSelectList(QueryWrapper<FileEntry> wrapper) {
            Set<Integer> ids = extractIntegerSet(wrapper.getParamNameValuePairs().values());
            return fileEntries.values().stream()
                    .filter(entry -> ids.contains(entry.getId()))
                    .map(this::cloneEntry)
                    .toList();
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
}
