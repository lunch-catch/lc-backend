package com.launchcatch.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.launchcatch.admin.dto.AdminRegistrationRequest;
import com.launchcatch.admin.dto.AdminRegistrationResponse;
import com.launchcatch.admin.service.AdminRegistrationService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AdminRegistrationControllerTest {
    @Test
    void 발급자의_정보를_전달하고_201로_응답한다() {
        var service = mock(AdminRegistrationService.class);
        var controller = new AdminRegistrationController(service);
        var request = new AdminRegistrationRequest("admin01", "Initial123!", "홍길동", "ADMIN");
        var expected = new AdminRegistrationResponse("admin01", "홍길동", "ADMIN");
        when(service.register(1L, Role.SUPER_ADMIN, request)).thenReturn(expected);
        var response = controller.register(new CustomUserDetails(1L, Role.SUPER_ADMIN), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("SUCCESS");
        assertThat(response.getBody().data()).isEqualTo(expected);
        verify(service).register(1L, Role.SUPER_ADMIN, request);
    }
}