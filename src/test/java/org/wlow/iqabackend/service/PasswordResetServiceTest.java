package org.wlow.iqabackend.service;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.data.entity.PasswordResetCode;
import org.wlow.iqabackend.data.entity.User;
import org.wlow.iqabackend.mapper.PasswordResetCodeMapper;
import org.wlow.iqabackend.mapper.UserMapper;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordResetServiceTest {

    @Test
    void sendResetCodeCreatesHashedCodeAndSendsMail() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(1, "alice", "alice@example.com", "$2a$10$old", UserRole.USER.name()));
        InMemoryPasswordResetCodeStore codeStore = new InMemoryPasswordResetCodeStore();
        RecordingMailService mailService = new RecordingMailService();
        PasswordResetService service = buildService(userStore, codeStore, mailService);

        service.sendResetCode(" alice@example.com ");

        PasswordResetCode storedCode = codeStore.findLatestByEmail("alice@example.com");
        assertNotNull(storedCode);
        assertEquals(1, mailService.deliveries().size());
        assertEquals("alice@example.com", mailService.deliveries().get(0).email());
        assertNotEquals(mailService.deliveries().get(0).code(), storedCode.getCodeHash());
        assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(mailService.deliveries().get(0).code(), storedCode.getCodeHash()));
    }

    @Test
    void sendResetCodeReturnsSilentlyWhenEmailNotFound() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        InMemoryPasswordResetCodeStore codeStore = new InMemoryPasswordResetCodeStore();
        RecordingMailService mailService = new RecordingMailService();
        PasswordResetService service = buildService(userStore, codeStore, mailService);

        service.sendResetCode("nobody@example.com");

        assertTrue(codeStore.records().isEmpty());
        assertTrue(mailService.deliveries().isEmpty());
    }

    @Test
    void sendResetCodeHonorsCooldown() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(1, "alice", "alice@example.com", "$2a$10$old", UserRole.USER.name()));
        InMemoryPasswordResetCodeStore codeStore = new InMemoryPasswordResetCodeStore();
        codeStore.seed(resetCode(
                1,
                1,
                "alice@example.com",
                "$2a$10$hash",
                LocalDateTime.now().plusMinutes(10),
                0,
                null,
                LocalDateTime.now().minusSeconds(30)
        ));
        RecordingMailService mailService = new RecordingMailService();
        PasswordResetService service = buildService(userStore, codeStore, mailService);

        service.sendResetCode("alice@example.com");

        assertEquals(1, codeStore.records().size());
        assertTrue(mailService.deliveries().isEmpty());
    }

    @Test
    void resetPasswordUpdatesPasswordAndConsumesCode() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(1, "alice", "alice@example.com", encoder.encode("old-pass"), UserRole.USER.name()));
        InMemoryPasswordResetCodeStore codeStore = new InMemoryPasswordResetCodeStore();
        codeStore.seed(resetCode(
                1,
                1,
                "alice@example.com",
                encoder.encode("123456"),
                LocalDateTime.now().plusMinutes(10),
                0,
                null,
                LocalDateTime.now().minusMinutes(1)
        ));
        PasswordResetService service = buildService(userStore, codeStore, new RecordingMailService());

        service.resetPassword("alice@example.com", "123456", "new-pass");

        User updatedUser = userStore.findByEmail("alice@example.com");
        PasswordResetCode updatedCode = codeStore.findById(1);
        assertTrue(encoder.matches("new-pass", updatedUser.getPassword()));
        assertNotNull(updatedCode.getConsumedAt());
    }

    @Test
    void resetPasswordIncrementsAttemptsWhenCodeIsWrong() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(1, "alice", "alice@example.com", encoder.encode("old-pass"), UserRole.USER.name()));
        InMemoryPasswordResetCodeStore codeStore = new InMemoryPasswordResetCodeStore();
        codeStore.seed(resetCode(
                1,
                1,
                "alice@example.com",
                encoder.encode("123456"),
                LocalDateTime.now().plusMinutes(10),
                0,
                null,
                LocalDateTime.now().minusMinutes(1)
        ));
        PasswordResetService service = buildService(userStore, codeStore, new RecordingMailService());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("alice@example.com", "654321", "new-pass"));

        PasswordResetCode updatedCode = codeStore.findById(1);
        assertEquals("验证码错误", exception.getMessage());
        assertEquals(1, updatedCode.getAttemptCount());
        assertNull(updatedCode.getConsumedAt());
    }

    @Test
    void resetPasswordConsumesCodeWhenAttemptsReachMax() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(1, "alice", "alice@example.com", encoder.encode("old-pass"), UserRole.USER.name()));
        InMemoryPasswordResetCodeStore codeStore = new InMemoryPasswordResetCodeStore();
        codeStore.seed(resetCode(
                1,
                1,
                "alice@example.com",
                encoder.encode("123456"),
                LocalDateTime.now().plusMinutes(10),
                4,
                null,
                LocalDateTime.now().minusMinutes(1)
        ));
        PasswordResetService service = buildService(userStore, codeStore, new RecordingMailService());

        assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword("alice@example.com", "654321", "new-pass"));

        PasswordResetCode updatedCode = codeStore.findById(1);
        assertEquals(5, updatedCode.getAttemptCount());
        assertNotNull(updatedCode.getConsumedAt());
    }

    private PasswordResetService buildService(InMemoryUserStore userStore,
                                              InMemoryPasswordResetCodeStore codeStore,
                                              RecordingMailService mailService) {
        PasswordResetService service = new PasswordResetService(userStore.mapper(), codeStore.mapper(), mailService);
        ReflectionTestUtils.setField(service, "codeExpirationSeconds", 600L);
        ReflectionTestUtils.setField(service, "sendCooldownSeconds", 60L);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        return service;
    }

    private User user(Integer id, String username, String email, String password, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(password);
        user.setRole(role);
        user.setRegisterTime(LocalDateTime.now().minusDays(1));
        return user;
    }

    private PasswordResetCode resetCode(Integer id,
                                        Integer userId,
                                        String email,
                                        String codeHash,
                                        LocalDateTime expiresAt,
                                        Integer attemptCount,
                                        LocalDateTime consumedAt,
                                        LocalDateTime createdAt) {
        PasswordResetCode code = new PasswordResetCode();
        code.setId(id);
        code.setUserId(userId);
        code.setEmail(email);
        code.setCodeHash(codeHash);
        code.setExpiresAt(expiresAt);
        code.setAttemptCount(attemptCount);
        code.setConsumedAt(consumedAt);
        code.setCreatedAt(createdAt);
        return code;
    }

    private static final class RecordingMailService extends MailService {
        private final List<Delivery> deliveries = new ArrayList<>();

        private RecordingMailService() {
            super(dummyMailSender());
        }

        @Override
        public void sendPasswordResetCode(String email, String code) {
            deliveries.add(new Delivery(email, code));
        }

        private List<Delivery> deliveries() {
            return deliveries;
        }
    }

    private record Delivery(String email, String code) {
    }

    private static final class InMemoryUserStore {
        private final Map<Integer, User> users = new LinkedHashMap<>();

        private void seed(User user) {
            users.put(user.getId(), cloneUser(user));
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
                            case "selectOne" -> handleSelectOne((com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>) args[0]);
                            case "updateById" -> handleUpdateById((User) args[0]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private User findByEmail(String email) {
            return users.values().stream()
                    .filter(user -> email.equals(user.getEmail()))
                    .findFirst()
                    .map(this::cloneUser)
                    .orElse(null);
        }

        private User handleSelectOne(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User> wrapper) {
            String sqlSegment = wrapper.getSqlSegment();
            Object value = wrapper.getParamNameValuePairs().values().stream().findFirst().orElse(null);
            if (sqlSegment.contains("email")) {
                return users.values().stream()
                        .filter(user -> java.util.Objects.equals(user.getEmail(), value))
                        .findFirst()
                        .map(this::cloneUser)
                        .orElse(null);
            }
            if (sqlSegment.contains("username")) {
                return users.values().stream()
                        .filter(user -> java.util.Objects.equals(user.getUsername(), value))
                        .findFirst()
                        .map(this::cloneUser)
                        .orElse(null);
            }
            throw new UnsupportedOperationException("unknown query: " + sqlSegment);
        }

        private int handleUpdateById(User user) {
            users.put(user.getId(), cloneUser(user));
            return 1;
        }

        private User cloneUser(User user) {
            if (user == null) {
                return null;
            }
            User clone = new User();
            clone.setId(user.getId());
            clone.setUsername(user.getUsername());
            clone.setEmail(user.getEmail());
            clone.setPassword(user.getPassword());
            clone.setRole(user.getRole());
            clone.setRegisterTime(user.getRegisterTime());
            return clone;
        }
    }

    private static final class InMemoryPasswordResetCodeStore {
        private final Map<Integer, PasswordResetCode> codes = new LinkedHashMap<>();
        private int nextId = 1;

        private void seed(PasswordResetCode code) {
            codes.put(code.getId(), cloneCode(code));
            nextId = Math.max(nextId, code.getId() + 1);
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
                            case "selectLatestByEmail" -> handleSelectLatestByEmail((String) args[0]);
                            case "selectLatestValidByEmail" -> handleSelectLatestValidByEmail((String) args[0], (LocalDateTime) args[1], (Integer) args[2]);
                            case "consumeActiveCodesByEmail" -> handleConsumeActiveCodesByEmail((String) args[0], (LocalDateTime) args[1]);
                            case "insert" -> handleInsert((PasswordResetCode) args[0]);
                            case "deleteById" -> codes.remove((Integer) args[0]) != null ? 1 : 0;
                            case "updateById" -> handleUpdateById((PasswordResetCode) args[0]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private List<PasswordResetCode> records() {
            return codes.values().stream().map(this::cloneCode).toList();
        }

        private PasswordResetCode findById(Integer id) {
            return cloneCode(codes.get(id));
        }

        private PasswordResetCode findLatestByEmail(String email) {
            return codes.values().stream()
                    .filter(code -> email.equals(code.getEmail()))
                    .max(Comparator.comparing(PasswordResetCode::getCreatedAt))
                    .map(this::cloneCode)
                    .orElse(null);
        }

        private PasswordResetCode handleSelectLatestByEmail(String email) {
            return findLatestByEmail(email);
        }

        private PasswordResetCode handleSelectLatestValidByEmail(String email, LocalDateTime now, Integer maxAttempts) {
            return codes.values().stream()
                    .filter(code -> email.equals(code.getEmail()))
                    .filter(code -> code.getConsumedAt() == null)
                    .filter(code -> code.getExpiresAt().isAfter(now))
                    .filter(code -> code.getAttemptCount() < maxAttempts)
                    .max(Comparator.comparing(PasswordResetCode::getCreatedAt))
                    .map(this::cloneCode)
                    .orElse(null);
        }

        private int handleConsumeActiveCodesByEmail(String email, LocalDateTime consumedAt) {
            List<PasswordResetCode> targets = codes.values().stream()
                    .filter(code -> email.equals(code.getEmail()))
                    .filter(code -> code.getConsumedAt() == null)
                    .toList();
            targets.forEach(code -> code.setConsumedAt(consumedAt));
            return targets.size();
        }

        private int handleInsert(PasswordResetCode code) {
            code.setId(nextId++);
            codes.put(code.getId(), cloneCode(code));
            return 1;
        }

        private int handleUpdateById(PasswordResetCode code) {
            codes.put(code.getId(), cloneCode(code));
            return 1;
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

    private static JavaMailSender dummyMailSender() {
        return (JavaMailSender) Proxy.newProxyInstance(
                JavaMailSender.class.getClassLoader(),
                new Class<?>[]{JavaMailSender.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "DummyJavaMailSender";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    throw new MailParseException("dummy sender should not be used directly");
                });
    }
}
