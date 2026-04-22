package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.mapper.FileEntryMapper;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileServiceStaticResourceTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadStoresImageInFlatDirectoryAndBuildsStaticUrl() throws Exception {
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();
        FileService fileService = new FileService(fileEntryStore.mapper(), tempDir.toString());

        FileEntry entry = fileService.upload("image-bytes".getBytes(), "sample.JPG", 2);

        Path imageDirectory = tempDir.resolve("image");
        Path storedPath = imageDirectory.resolve(entry.getFilename() + "." + entry.getExtname());

        assertEquals(imageDirectory.toAbsolutePath().normalize().toString(), entry.getDirectory());
        assertEquals("/resource/image/" + entry.getFilename() + ".jpg", entry.getResourceURL());
        assertTrue(Files.exists(storedPath));
    }

    @Test
    void mapFileEntriesByIdsReturnsStaticImageUrl() {
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();
        FileService fileService = new FileService(fileEntryStore.mapper(), tempDir.toString());

        fileEntryStore.seed(fileEntry(1, 2, "uuid-a", "jpg", tempDir.resolve("image").toString()));

        Map<Integer, FileEntry> result = fileService.mapFileEntriesByIds(List.of(1));

        assertEquals("/resource/image/uuid-a.jpg", result.get(1).getResourceURL());
    }

    @Test
    void deleteOwnedFileRemovesFlatStoredImage() throws Exception {
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();
        FileService fileService = new FileService(fileEntryStore.mapper(), tempDir.toString());

        Path imageDirectory = tempDir.resolve("image");
        Files.createDirectories(imageDirectory);
        Path storedPath = imageDirectory.resolve("uuid-a.jpg");
        Files.writeString(storedPath, "image-bytes");
        fileEntryStore.seed(fileEntry(1, 2, "uuid-a", "jpg", imageDirectory.toString()));

        fileService.deleteOwnedFile(1, 2);

        assertTrue(fileEntryStore.records().isEmpty());
        assertFalse(Files.exists(storedPath));
    }

    private FileEntry fileEntry(Integer id, Integer userId, String filename, String extname, String directory) {
        FileEntry entry = new FileEntry();
        entry.setId(id);
        entry.setUserId(userId);
        entry.setFilename(filename);
        entry.setExtname(extname);
        entry.setDirectory(directory);
        entry.setSize(11L);
        entry.setUploadTime(LocalDateTime.now());
        return entry;
    }

    private static final class InMemoryFileEntryStore {
        private final Map<Integer, FileEntry> fileEntries = new LinkedHashMap<>();
        private int nextId = 1;

        private void seed(FileEntry entry) {
            fileEntries.put(entry.getId(), cloneEntry(entry));
            nextId = Math.max(nextId, entry.getId() + 1);
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
                            case "insert" -> handleInsert((FileEntry) args[0]);
                            case "selectById" -> cloneEntry(fileEntries.get((Integer) args[0]));
                            case "selectList" -> handleSelectList((QueryWrapper<FileEntry>) args[0]);
                            case "deleteById" -> fileEntries.remove((Integer) args[0]) != null ? 1 : 0;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private int handleInsert(FileEntry entry) {
            FileEntry clone = cloneEntry(entry);
            clone.setId(nextId++);
            fileEntries.put(clone.getId(), clone);
            entry.setId(clone.getId());
            return 1;
        }

        private List<FileEntry> handleSelectList(QueryWrapper<FileEntry> wrapper) {
            String sqlSegment = wrapper.getSqlSegment();
            Set<Integer> ids = extractIntegerSet(wrapper.getParamNameValuePairs().values());
            if (sqlSegment.contains("id IN")) {
                return fileEntries.values().stream()
                        .filter(entry -> ids.contains(entry.getId()))
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

        private Set<Integer> extractIntegerSet(Iterable<?> values) {
            Set<Integer> result = new LinkedHashSet<>();
            for (Object value : values) {
                collectIntegerValues(value, result);
            }
            return result;
        }

        private void collectIntegerValues(Object value, Set<Integer> target) {
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
}
