package org.wlow.iqabackend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.exception.FileAccessDeniedException;
import org.wlow.iqabackend.mapper.FileEntryMapper;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void uploadAndLoadOwnedImageStoreMetadataAndBytes() throws IOException {
        InMemoryFileEntryStore store = new InMemoryFileEntryStore(false);
        FileService fileService = new FileService(store.mapper(), tempDir.toString());

        byte[] imageBytes = new byte[]{1, 2, 3, 4};
        FileEntry entry = fileService.upload(imageBytes, "sample.PNG", 7);

        assertEquals(1, entry.getId());
        assertEquals(7, entry.getUserId());
        assertEquals("png", entry.getExtname());
        assertNotEquals("sample", entry.getFilename());
        assertEquals("/resource/image/1", entry.getResourceURL());
        assertTrue(Path.of(entry.getDirectory()).isAbsolute());

        Path storedPath = Path.of(entry.getDirectory(), entry.getFilename() + "." + entry.getExtname());
        assertTrue(Files.exists(storedPath));

        FileService.StoredResource storedResource = fileService.loadOwnedImage(entry.getId(), 7);
        assertEquals("image/png", storedResource.contentType().toString());
        assertEquals(imageBytes.length, storedResource.size());
        assertArrayEquals(imageBytes, storedResource.resource().getInputStream().readAllBytes());

        fileService.deleteOwnedFile(entry.getId(), 7);
        assertFalse(Files.exists(storedPath));
        assertTrue(store.entries().isEmpty());
    }

    @Test
    void uploadRejectsInvalidExtname() {
        InMemoryFileEntryStore store = new InMemoryFileEntryStore(false);
        FileService fileService = new FileService(store.mapper(), tempDir.toString());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> fileService.upload(new byte[]{1}, "sample", 7));

        assertEquals("文件扩展名不合法", exception.getMessage());
    }

    @Test
    void loadOwnedImageRejectsOtherUsers() {
        InMemoryFileEntryStore store = new InMemoryFileEntryStore(false);
        FileService fileService = new FileService(store.mapper(), tempDir.toString());
        FileEntry entry = fileService.upload(new byte[]{1}, "sample.jpg", 7);

        FileAccessDeniedException exception = assertThrows(FileAccessDeniedException.class,
                () -> fileService.loadOwnedImage(entry.getId(), 8));

        assertEquals("无权访问该图片", exception.getMessage());
    }

    @Test
    void uploadRollsBackPhysicalFileWhenInsertFails() throws IOException {
        InMemoryFileEntryStore store = new InMemoryFileEntryStore(true);
        FileService fileService = new FileService(store.mapper(), tempDir.toString());

        assertThrows(RuntimeException.class, () -> fileService.upload(new byte[]{9, 9}, "failed.jpg", 7));

        Path userDir = tempDir.resolve("image").resolve("7");
        if (Files.exists(userDir)) {
            try (var files = Files.list(userDir)) {
                assertEquals(0, files.count());
            }
        }
    }

    private static final class InMemoryFileEntryStore {
        private final Map<Integer, FileEntry> entries = new LinkedHashMap<>();
        private final boolean failOnInsert;
        private int nextId = 1;

        private InMemoryFileEntryStore(boolean failOnInsert) {
            this.failOnInsert = failOnInsert;
        }

        private Map<Integer, FileEntry> entries() {
            return entries;
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
                            case "selectById" -> entries.get((Integer) args[0]);
                            case "deleteById" -> entries.remove((Integer) args[0]) != null ? 1 : 0;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private int handleInsert(FileEntry entry) {
            if (failOnInsert) {
                throw new RuntimeException("insert failed");
            }
            entry.setId(nextId++);
            entries.put(entry.getId(), cloneEntry(entry));
            return 1;
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
