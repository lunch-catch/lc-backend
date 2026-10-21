package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.jwt.CutoffPolicy;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAccountQueryServiceTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 21, 12, 0);
    @Mock AdminRepository repository;
    @Mock AdminAccessTokenVerifier verifier;
    @InjectMocks AdminAccountQueryService service;

    @Test
    void 서비스_재검증에서_폐기된_토큰을_거절한다() {
        when(verifier.isValidAfter(Role.SUPER_ADMIN, 1L, TIME, 1L, CutoffPolicy.REQUIRED)).thenReturn(false);
        assertThatThrownBy(() -> service.list(1L, Role.SUPER_ADMIN, TIME, 1L, null))
                .isInstanceOf(AuthException.class).extracting("errorCode").isEqualTo(AuthErrorCode.LOGIN_REQUIRED);
    }

    @Test
    void 현재_요청자_계정이_없으면_거절한다() {
        when(verifier.isValidAfter(Role.SUPER_ADMIN, 1L, TIME, 1L, CutoffPolicy.REQUIRED)).thenReturn(true);
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(1L, Role.SUPER_ADMIN, TIME, 1L, null))
                .isInstanceOf(AuthException.class).extracting("errorCode").isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED);
    }
}
