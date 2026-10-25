package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.OwnerLoginRequest;
import com.launchcatch.owner.dto.OwnerLoginResponse;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OwnerLoginServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
    private final OwnerSessionService sessions = mock(OwnerSessionService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final OwnerLoginRequest request = new OwnerLoginRequest("owner@example.com", "password12");
    private Owner owner;
    private OwnerLoginService service;

    @BeforeEach
    void setUp() {
        owner = Owner.create(request.email(), encoder.encode(request.password()));
        ReflectionTestUtils.setField(owner, "id", 7L);
        when(owners.findByEmail(request.email())).thenReturn(Optional.of(owner));
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(sessions.issue(owner)).thenReturn(new OwnerLoginResult(
                new OwnerLoginResponse(request.email(), Role.OWNER, OwnerStatus.ONBOARDING, null), "access", "refresh"));
        service = new OwnerLoginService(owners, encoder, sessions, manager);
    }

    @Test
    void 비밀번호를_확인한_점주에게_독립된_세션을_발급한다() {
        var result = service.login(request);
        assertThat(result.refreshToken()).isEqualTo("refresh");
        assertThat(result.toString()).doesNotContain("access", "refresh", request.email());
        verify(manager).commit(any());
    }

    @Test
    void 미존재_계정도_BCrypt_비교를_수행한다() {
        when(owners.findByEmail(request.email())).thenReturn(Optional.empty());
        assertError(request, "AUTH-001");
        verify(encoder).matches(eq(request.password()), argThat(hash -> hash.startsWith("$2")));
        verifyNoInteractions(sessions);
    }

    @Test
    void 틀린_비밀번호로는_세션을_발급하지_않는다() {
        assertError(new OwnerLoginRequest(request.email(), "wrong-password"), "AUTH-001");
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void 정지와_탈퇴_계정은_로그인하지_못한다(OwnerStatus status) {
        ReflectionTestUtils.setField(owner, "status", status);
        assertError(request, "AUTH-001");
        verifyNoInteractions(sessions);
    }

    @Test
    void 잠금_대기_중_계정이_삭제되면_거부한다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertError(request, "AUTH-001");
    }

    @Test
    void 비밀번호_비교_후_정지되면_거부한다() {
        Owner changed = Owner.create(request.email(), owner.getPasswordHash());
        ReflectionTestUtils.setField(changed, "status", OwnerStatus.SUSPENDED);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(changed));
        assertError(request, "AUTH-001");
        verifyNoInteractions(sessions);
    }

    @Test
    void 비밀번호_비교_후_비밀번호가_바뀌면_거부한다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(Owner.create(request.email(), "changed")));
        assertError(request, "AUTH-001");
        verifyNoInteractions(sessions);
    }

    @Test
    void 세션_저장_실패는_503이다() {
        when(sessions.issue(owner)).thenThrow(new DataAccessResourceFailureException("DB"));
        assertError(request, "AUTH-002");
    }

    @Test
    void 커밋_실패는_토큰을_반환하지_않는다() {
        doThrow(new UnexpectedRollbackException("commit")).when(manager).commit(any());
        assertError(request, "AUTH-002");
    }

    private void assertError(OwnerLoginRequest input, String code) {
        assertThatThrownBy(() -> service.login(input)).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
    }
}
