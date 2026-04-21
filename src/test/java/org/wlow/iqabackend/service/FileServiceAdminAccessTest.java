package org.wlow.iqabackend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wlow.iqabackend.data.entity.FileEntry;
import org.wlow.iqabackend.exception.FileAccessDeniedException;
import org.wlow.iqabackend.mapper.FileEntryMapper;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileServiceAdminAccessTest {

    @TempDir
    Path tempDir;

    @Test
    void loadAccessibleImageAllowsAdminToReadOtherUsersFile() throws Exception {
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();
        FileService fileService = new FileService(fileEntryStore.mapper(), tempDir.toString());

        Path userDirectory = tempDir.resolve("image").resolve("2");
        Files.createDirectories(userDirectory);
        Path storedPath = userDirectory.resolve("sample-file.jpg");
        Files.writeString(storedPath, "image-bytes");

        fileEntryStore.seed(fileEntry(1, 2, "sample-file", "jpg", userDirectory.toString()));

        FileService.StoredResource resource = fileService.loadAccessibleImage(1, 99, "ADMIN");

        assertEquals(11, resource.size());
        assertEquals("image/jpeg", resource.contentType().toString());
    }

    @Test
    void loadAccessibleImageRejectsExtendedNonAdminRole() throws Exception {
        InMemoryFileEntryStore fileEntryStore = new InMemoryFileEntryStore();
        FileService fileService = new FileService(fileEntryStore.mapper(), tempDir.toString());

        Path userDirectory = tempDir.resolve("image").resolve("2");
        Files.createDirectories(userDirectory);
        Files.writeString(userDirectory.resolve("sample-file.jpg"), "image-bytes");
        fileEntryStore.seed(fileEntry(1, 2, "sample-file", "jpg", userDirectory.toString()));

        assertThrows(FileAccessDeniedException.class,
                () -> fileService.loadAccessibleImage(1, 99, "EXPERT"));
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

        private void seed(FileEntry entry) {
            fileEntries.put(entry.getId(), cloneEntry(entry));
        }

        private FileEntry findById(Integer id) {
            return cloneEntry(fileEntries.get(id));
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
                            case "selectById" -> cloneEntry(fileEntries.get((Integer) args[0]));
                            case "deleteById" -> fileEntries.remove((Integer) args[0]) != null ? 1 : 0;
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
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
}
