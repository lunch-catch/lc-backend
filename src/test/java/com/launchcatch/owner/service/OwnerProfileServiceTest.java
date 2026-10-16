package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.store.contract.StoreRegistrationQuery;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class OwnerProfileServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final StoreRegistrationQuery registrationQuery = mock(StoreRegistrationQuery.class);
    private final OwnerProfileService service = new OwnerProfileService(owners, registrationQuery);

    @ParameterizedTest
    @EnumSource(OwnerStatus.class)
    void 현재_DB의_상태와_튜토리얼_확인_여부를_반환한다(OwnerStatus status) {
        Owner owner = Owner.create("owner@example.com", "password-hash");
        ReflectionTestUtils.setField(owner, "status", status);
        when(owners.findById(7L)).thenReturn(Optional.of(owner));

        var response = service.getMyProfile(7L);

        assertThat(response.email()).isEqualTo("owner@example.com");
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.tutorialViewed()).isFalse();
    }

    @Test
    void 온보딩_점주는_저장된_재개_단계를_함께_반환한다() {
        Owner owner = Owner.create("owner@example.com", "password-hash");
        when(owners.findById(7L)).thenReturn(Optional.of(owner));
        when(registrationQuery.findResumeStep(7L)).thenReturn("HOURS");
        assertThat(service.getMyProfile(7L).registrationStep()).isEqualTo("HOURS");
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = "ONBOARDING", mode = EnumSource.Mode.EXCLUDE)
    void 온보딩_상태가_아니면_재개_단계는_없다(OwnerStatus status) {
        Owner owner = Owner.create("owner@example.com", "password-hash");
        ReflectionTestUtils.setField(owner, "status", status);
        when(owners.findById(7L)).thenReturn(Optional.of(owner));
        assertThat(service.getMyProfile(7L).registrationStep()).isNull();
    }

    @Test
    void 튜토리얼을_완료한_점주는_true를_반환한다() {
        Owner owner = Owner.create("owner@example.com", "password-hash");
        ReflectionTestUtils.setField(owner, "status", OwnerStatus.ACTIVE);
        ReflectionTestUtils.setField(owner, "tutorialViewed", true);
        when(owners.findById(7L)).thenReturn(Optional.of(owner));
        assertThat(service.getMyProfile(7L).tutorialViewed()).isTrue();
    }

    @Test
    void 계정이_없으면_로그인을_요구한다() {
        when(owners.findById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMyProfile(7L))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_REQUIRED));
    }

    @Test
    void DB_장애를_성공으로_응답하지_않는다() {
        when(owners.findById(7L)).thenThrow(new DataAccessResourceFailureException("DB unavailable"));
        assertThatThrownBy(() -> service.getMyProfile(7L)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}