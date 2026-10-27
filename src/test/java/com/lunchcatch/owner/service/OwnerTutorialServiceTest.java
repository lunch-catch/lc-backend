package com.lunchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.owner.entity.Owner;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.exception.OwnerException;
import com.lunchcatch.owner.repository.OwnerRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class OwnerTutorialServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final OwnerTutorialService service = new OwnerTutorialService(owners);

    private Owner owner(OwnerStatus status) {
        Owner owner = Owner.create("owner@example.com", "hash");
        ReflectionTestUtils.setField(owner, "status", status);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        return owner;
    }

    @Test
    void 완료와_반복_요청은_토큰이나_상태를_변경하지_않는다() {
        var owner = owner(OwnerStatus.ACTIVE);
        var now = LocalDateTime.now();
        owner.recordLogin("a".repeat(64), now.plusDays(14), now);
        assertThat(service.complete(7L).tutorialViewed()).isTrue();
        assertThat(service.complete(7L).tutorialViewed()).isTrue();
        assertThat(owner.getStatus()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(owner.getRefreshTokenHash()).isEqualTo("a".repeat(64));
        assertThat(owner.getRefreshTokenExpiresAt()).isEqualTo(now.plusDays(14));
        assertThat(owner.getRefreshTokenIssuanceVersion()).isEqualTo(1);
        assertThat(owner.getLastLoginAt()).isEqualTo(now);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void ACTIVE가_아니면_완료할_수_없다(OwnerStatus status) {
        var owner = owner(status);
        assertThatThrownBy(() -> service.complete(7L)).isInstanceOfSatisfying(OwnerException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("OWNER-002"));
        assertThat(owner.isTutorialViewed()).isFalse();
        verify(owners, never()).saveAndFlush(any());
    }

    @Test
    void 완료한_점주도_현재_ACTIVE가_아니면_거부한다() {
        var owner = owner(OwnerStatus.ACTIVE);
        owner.completeTutorial();
        ReflectionTestUtils.setField(owner, "status", OwnerStatus.SUSPENDED);
        assertThatThrownBy(() -> service.complete(7L)).isInstanceOf(OwnerException.class);
    }

    @Test
    void 계정이_없으면_다시_로그인해야_한다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.complete(7L)).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-005"));
    }

    @Test
    void DB_실패를_성공으로_처리하지_않는다() {
        owner(OwnerStatus.ACTIVE);
        var failure = new DataAccessResourceFailureException("test failure");
        when(owners.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.complete(7L)).isSameAs(failure);
    }
}
