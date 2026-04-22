package org.wlow.iqabackend.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.UserRole;
import org.wlow.iqabackend.service.JwtService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthInterceptorRoleTest {

    @AfterEach
    void tearDown() {
        CurrentUser.clearAll();
    }

    @Test
    void preHandleRestoresRoleFromToken() throws Exception {
        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", "3q4-jb!@-$%@(%vgUYT&:s-dbehj@AWDnwadamf");
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 3600L);

        String token = jwtService.generateAccessToken("alice", 7, UserRole.ADMIN.name());
        AuthInterceptor authInterceptor = new AuthInterceptor(jwtService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", token);

        boolean allowed = authInterceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(7, CurrentUser.getId());
        assertEquals("alice", CurrentUser.getUsername());
        assertEquals(UserRole.ADMIN.name(), CurrentUser.getRole());
    }

    @Test
    void preHandleAllowsStaticImageRequestWithoutToken() throws Exception {
        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", "3q4-jb!@-$%@(%vgUYT&:s-dbehj@AWDnwadamf");
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 3600L);

        AuthInterceptor authInterceptor = new AuthInterceptor(jwtService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/resource/image/sample-uuid.jpg");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = authInterceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertNull(CurrentUser.getId());
    }
}
