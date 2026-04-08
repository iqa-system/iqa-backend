package org.wlow.iqabackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.data.dto.Response;
import org.wlow.iqabackend.data.dto.UserInfo;
import org.wlow.iqabackend.data.entity.User;
import org.wlow.iqabackend.mapper.UserMapper;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserAccountSupportTest {

    @AfterEach
    void tearDown() {
        CurrentUser.clearAll();
    }

    @Test
    void signUpStoresEmailAndDefaultRole() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        AuthService authService = new AuthService(userStore.mapper(), new JwtService());

        Response<String> response = authService.signUp("alice", "password123", " alice@example.com ");

        User savedUser = userStore.findByUsername("alice");
        assertNotNull(savedUser);
        assertEquals(200, response.getCode());
        assertEquals("alice@example.com", savedUser.getEmail());
        assertEquals(UserRole.USER.name(), savedUser.getRole());
    }

    @Test
    void signUpRejectsDuplicateEmail() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(1, "alice", "alice@example.com", UserRole.USER.name()));
        AuthService authService = new AuthService(userStore.mapper(), new JwtService());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> authService.signUp("bob", "password123", "alice@example.com"));

        assertEquals("邮箱已存在", exception.getMessage());
    }

    @Test
    void getUserInfoIncludesEmailAndRole() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(7, "alice", "alice@example.com", UserRole.ADMIN.name()));
        UserService userService = new UserService(userStore.mapper());
        CurrentUser.setAll(7, "alice", UserRole.ADMIN.name());

        UserInfo userInfo = userService.getUserInfo();

        assertEquals("alice@example.com", userInfo.getEmail());
        assertEquals(UserRole.ADMIN.name(), userInfo.getRole());
    }

    @Test
    void updateUserInfoRejectsDuplicateEmail() {
        InMemoryUserStore userStore = new InMemoryUserStore();
        userStore.seed(user(1, "alice", "alice@example.com", UserRole.USER.name()));
        userStore.seed(user(2, "bob", "bob@example.com", UserRole.USER.name()));
        UserService userService = new UserService(userStore.mapper());
        CurrentUser.setAll(1, "alice", UserRole.USER.name());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> userService.updateUserInfo(null, "bob@example.com", null, null));

        assertEquals("邮箱已存在", exception.getMessage());
    }

    private User user(Integer id, String username, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("$2a$10$dummy");
        user.setEmail(email);
        user.setRole(role);
        user.setRegisterTime(LocalDateTime.of(2026, 4, 8, 13, 0, 0));
        return user;
    }

    private static final class InMemoryUserStore {
        private final Map<Integer, User> users = new LinkedHashMap<>();
        private int nextId = 1;

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
                            case "insert" -> handleInsert((User) args[0]);
                            case "selectOne" -> handleSelectOne((QueryWrapper<User>) args[0]);
                            case "selectById" -> cloneUser(users.get((Integer) args[0]));
                            case "updateById" -> handleUpdateById((User) args[0]);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }

        private void seed(User user) {
            users.put(user.getId(), cloneUser(user));
            nextId = Math.max(nextId, user.getId() + 1);
        }

        private User findByUsername(String username) {
            return users.values().stream()
                    .filter(user -> username.equals(user.getUsername()))
                    .findFirst()
                    .map(this::cloneUser)
                    .orElse(null);
        }

        private int handleInsert(User user) {
            user.setId(nextId++);
            users.put(user.getId(), cloneUser(user));
            return 1;
        }

        private User handleSelectOne(QueryWrapper<User> wrapper) {
            String sqlSegment = wrapper.getSqlSegment();
            Object value = wrapper.getParamNameValuePairs().values().stream().findFirst().orElse(null);
            if (sqlSegment.contains("username")) {
                return users.values().stream()
                        .filter(user -> java.util.Objects.equals(user.getUsername(), value))
                        .findFirst()
                        .map(this::cloneUser)
                        .orElse(null);
            }
            if (sqlSegment.contains("email")) {
                return users.values().stream()
                        .filter(user -> java.util.Objects.equals(user.getEmail(), value))
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
            clone.setPassword(user.getPassword());
            clone.setEmail(user.getEmail());
            clone.setRole(user.getRole());
            clone.setRegisterTime(user.getRegisterTime());
            return clone;
        }
    }
}
