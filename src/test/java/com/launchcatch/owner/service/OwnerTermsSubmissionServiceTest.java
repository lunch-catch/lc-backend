package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.OwnerTermsAgreementRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.OwnerTermsAgreementRecorder;
import com.launchcatch.store.contract.TermsAgreementCommand;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class OwnerTermsSubmissionServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final OwnerTermsAgreementRecorder terms = mock(OwnerTermsAgreementRecorder.class);
    private final OwnerTermsSubmissionService service = new OwnerTermsSubmissionService(owners, terms);
    private final OwnerTermsAgreementRequest request = new OwnerTermsAgreementRequest(0L, List.of(
            new OwnerTermsAgreementRequest.Agreement("SERVICE", "1.0", true)));

    @Test
    void 현재_온보딩_점주의_동의를_가게_계약으로_전달한다() {
        Owner owner = Owner.create("owner@example.com", "hash");
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        when(terms.recordAgreements(eq(7L), eq(0L), anyList())).thenReturn(new com.launchcatch.store.contract.TermsAgreementResult(true, 1L, "STORE_INFO"));
        assertThat(service.submit(7L, request).requiredTermsCompleted()).isTrue();
        verify(terms).recordAgreements(7L, 0L, List.of(new TermsAgreementCommand("SERVICE", "1.0", true)));
        assertThat(owner.getStatus()).isEqualTo(OwnerStatus.ONBOARDING);
        assertThat(owner.isTutorialViewed()).isFalse();
        assertThat(owner.getRefreshTokenHash()).isNull();
        assertThat(owner.getRefreshTokenIssuanceVersion()).isZero();
        verify(owners, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ACTIVE", "SUSPENDED", "WITHDRAWN"})
    void 온보딩_외_상태는_거부한다(OwnerStatus status) {
        Owner owner = Owner.create("owner@example.com", "hash");
        ReflectionTestUtils.setField(owner, "status", status);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        assertThatThrownBy(() -> service.submit(7L, request)).isInstanceOfSatisfying(OwnerException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(OwnerErrorCode.STATUS_NOT_ALLOWED));
        verifyNoInteractions(terms);
    }

    @Test
    void 삭제된_점주는_인증_실패이다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit(7L, request)).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_REQUIRED));
        verifyNoInteractions(terms);
    }
}
