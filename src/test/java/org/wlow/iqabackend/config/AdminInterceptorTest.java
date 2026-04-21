package org.wlow.iqabackend.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.wlow.iqabackend.data.CurrentUser;
import org.wlow.iqabackend.data.UserRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminInterceptorTest {

    @AfterEach
    void tearDown() {
        CurrentUser.clearAll();
    }

    @Test
    void preHandleAllowsAdmin() throws Exception {
        AdminInterceptor interceptor = new AdminInterceptor();
        CurrentUser.setAll(1, "admin", UserRole.ADMIN.name());

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void preHandleRejectsNonAdminRoleEvenWhenRoleIsExtended() throws Exception {
        AdminInterceptor interceptor = new AdminInterceptor();
        CurrentUser.setAll(2, "expert", "EXPERT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }
}
