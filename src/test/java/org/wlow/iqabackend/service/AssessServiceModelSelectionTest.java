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
import org.wlow.iqabackend.data.dto.ModelInfo;
import org.wlow.iqabackend.data.dto.ModelsResponse;
import org.wlow.iqabackend.data.entity.AssessRecord;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.mapper.AssessRecordMapper;
import org.wlow.iqabackend.mapper.FileEntryMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssessServiceModelSelectionTest {

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
    void listModelsProxiesPythonModelsResponse() throws Exception {
        AssessModelFixture fixture = buildFixture();

        ModelsResponse response = fixture.assessService().listModels();

        assertEquals("liqe_default", response.getDefaultModelId());
        assertEquals(2, response.getModels().size());
        ModelInfo firstModel = response.getModels().get(0);
        assertEquals("liqe_default", firstModel.getModelId());
        assertTrue(firstModel.getCapabilities().contains("scene"));
        assertEquals(0D, firstModel.getScoreRange().getMinScore());
        assertEquals(100D, firstModel.getScoreRange().getMaxScore());
        assertEquals("TOPIQ-NR KonIQ", response.getModels().get(1).getDisplayName());
        assertEquals(0D, response.getModels().get(1).getScoreRange().getMinScore());
        assertEquals(1D, response.getModels().get(1).getScoreRange().getMaxScore());
    }

    @Test
    void assessPersistsReturnedDefaultModelIdWhenCallerOmitsModelId() throws Exception {
        AssessModelFixture fixture = buildFixture();
        CurrentUser.setAll(21, "alice", "USER");

        var result = fixture.assessService().assess(mockFile("sample.jpg"), null, null);

        AssessRecord stored = fixture.assessStore().records().get(0);
        assertEquals("liqe_default", stored.getModelId());
        assertEquals(4.3D, stored.getQualityScore(), 0.000001D);
        assertEquals(0D, result.getScoreRange().getMinScore());
        assertEquals(100D, result.getScoreRange().getMaxScore());
        assertTrue(fixture.lastPredictRequestBody().get().contains("name=\"file\""));
        assertTrue(!fixture.lastPredictRequestBody().get().contains("name=\"model_id\""));

        Page<AssessRecord> history = fixture.assessService().history(1, 10);
        assertEquals(1, history.getTotal());
        assertEquals("liqe_default", history.getRecords().get(0).getModelId());
    }

    @Test
    void assessForwardsRequestedModelIdAndAllowsNullSceneAndDistortions() throws Exception {
        AssessModelFixture fixture = buildFixture();
        CurrentUser.setAll(22, "alice", "USER");

        var result = fixture.assessService().assess(mockFile("topiq.jpg"), null, "topiq_nr_koniq");

        String requestBody = fixture.lastPredictRequestBody().get();
        assertTrue(requestBody.contains("name=\"model_id\""));
        assertTrue(requestBody.contains("topiq_nr_koniq"));
        assertEquals(0D, result.getScoreRange().getMinScore());
        assertEquals(1D, result.getScoreRange().getMaxScore());

        AssessRecord stored = fixture.assessStore().records().get(0);
        assertEquals("topiq_nr_koniq", stored.getModelId());
        assertNull(stored.getScene());
        assertNull(stored.getDistortions());
    }

    @Test
    void assessMapsPythonBadRequestToLocalIllegalArgumentException() throws Exception {
        AssessModelFixture fixture = buildFixture();
        CurrentUser.setAll(23, "alice", "USER");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> fixture.assessService().assess(mockFile("bad.jpg"), null, "unknown-model"));

        assertTrue(exception.getMessage().contains("未知模型: unknown-model"));
        assertTrue(fixture.assessStore().records().isEmpty());
    }

    @Test
    void assessBatchPersistsReturnedModelIdForEveryRecord() throws Exception {
        AssessModelFixture fixture = buildFixture();
        CurrentUser.setAll(24, "alice", "USER");

        fixture.assessService().assessBatchStream(
                List.of(mockFile("a.jpg"), mockFile("b.jpg")),
                null,
                "topiq_nr_koniq"
        );

        waitUntilRecords(fixture.assessStore(), 2);
        List<AssessRecord> records = fixture.assessStore().records();
        assertEquals("topiq_nr_koniq", records.get(0).getModelId());
        assertEquals("topiq_nr_koniq", records.get(1).getModelId());
        assertNull(records.get(0).getScene());
        assertNull(records.get(0).getDistortions());
        assertTrue(fixture.lastBatchRequestBody().get().contains("topiq_nr_koniq"));
    }

    private AssessModelFixture buildFixture() throws Exception {
        InMemoryAssessRecordStore assessStore = new InMemoryAssessRecordStore();
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();
        FileService fileService = new FileService(fileEntryStore.mapper(), tempDir.toString());
        AtomicReference<String> lastPredictRequestBody = new AtomicReference<>();
        AtomicReference<String> lastBatchRequestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/models", exchange -> writeJson(exchange, """
                {
                  "default_model_id": "liqe_default",
                  "models": [
                    {
                      "model_id": "liqe_default",
                      "display_name": "LIQE Default",
                      "description": "LIQE default model",
                      "capabilities": ["quality_score", "scene", "distortions"],
                      "score_range": {
                        "min_score": 0,
                        "max_score": 100
                      },
                      "is_default": true
                    },
                    {
                      "model_id": "topiq_nr_koniq",
                      "display_name": "TOPIQ-NR KonIQ",
                      "description": "TOPIQ model",
                      "capabilities": ["quality_score"],
                      "score_range": {
                        "min_score": 0,
                        "max_score": 1
                      },
                      "is_default": false
                    }
                  ]
                }
                """));
        server.createContext("/predict", exchange -> handlePredict(exchange, lastPredictRequestBody));
        server.createContext("/predict/batch", exchange -> handleBatchPredict(exchange, lastBatchRequestBody));
        server.start();
        this.httpServer = server;

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build();
        AssessService assessService = new AssessService(restClient, assessStore.mapper(), fileService);
        return new AssessModelFixture(assessService, assessStore, lastPredictRequestBody, lastBatchRequestBody);
    }

    private void handlePredict(HttpExchange exchange, AtomicReference<String> lastRequestBody) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastRequestBody.set(body);
        if (body.contains("unknown-model")) {
            writeJson(exchange, 400, """
                    {
                      "detail": "未知模型: unknown-model"
                    }
                    """);
            return;
        }
        if (body.contains("topiq_nr_koniq")) {
            writeJson(exchange, """
                    {
                      "file_name": "topiq.jpg",
                      "model_id": "topiq_nr_koniq",
                      "quality_score": 3.7,
                      "score_range": {
                        "min_score": 0,
                        "max_score": 1
                      },
                      "extra_outputs": {},
                      "scene": null,
                      "distortions": null
                    }
                    """);
            return;
        }
        writeJson(exchange, """
                {
                  "file_name": "sample.jpg",
                  "model_id": "liqe_default",
                  "quality_score": 4.3,
                  "score_range": {
                    "min_score": 0,
                    "max_score": 100
                  },
                  "extra_outputs": {},
                  "scene": 2,
                  "distortions": [
                    { "index": 1, "prob": 0.91 }
                  ]
                }
                """);
    }

    private void handleBatchPredict(HttpExchange exchange, AtomicReference<String> lastRequestBody) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastRequestBody.set(body);
        writeJson(exchange, """
                {
                  "results": [
                    {
                      "file_name": "a.jpg",
                      "model_id": "topiq_nr_koniq",
                      "quality_score": 3.8,
                      "score_range": {
                        "min_score": 0,
                        "max_score": 1
                      },
                      "extra_outputs": {},
                      "scene": null,
                      "distortions": null
                    },
                    {
                      "file_name": "b.jpg",
                      "model_id": "topiq_nr_koniq",
                      "quality_score": 3.5,
                      "score_range": {
                        "min_score": 0,
                        "max_score": 1
                      },
                      "extra_outputs": {},
                      "scene": null,
                      "distortions": null
                    }
                  ]
                }
                """);
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
        writeJson(exchange, 200, body);
    }

    private void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private record AssessModelFixture(
            AssessService assessService,
            InMemoryAssessRecordStore assessStore,
            AtomicReference<String> lastPredictRequestBody,
            AtomicReference<String> lastBatchRequestBody) {
    }

    private static final class InMemoryAssessRecordStore {
        private final Map<Integer, AssessRecord> records = new LinkedHashMap<>();
        private int nextId = 1;

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
                            case "selectPage" -> handleSelectPage((Page<AssessRecord>) args[0], (QueryWrapper<AssessRecord>) args[1]);
                            case "selectByIdList" -> handleSelectByIdList((List<Integer>) args[0]);
                            case "deleteByUserIdAndIds" -> handleDeleteByUserIdAndIds((Integer) args[0], (List<Integer>) args[1]);
                            case "selectById" -> cloneRecord(records.get((Integer) args[0]));
                            case "updateById" -> handleUpdateById((AssessRecord) args[0]);
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

        private int handleUpdateById(AssessRecord record) {
            if (!records.containsKey(record.getId())) {
                return 0;
            }
            records.put(record.getId(), cloneRecord(record));
            return 1;
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
