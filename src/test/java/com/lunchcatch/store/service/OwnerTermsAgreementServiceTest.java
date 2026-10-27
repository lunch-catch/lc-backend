package com.lunchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunchcatch.store.entity.OwnerTermsAgreement;
import com.lunchcatch.store.entity.StoreRegistrationProgress;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.exception.StoreErrorCode;
import com.lunchcatch.store.exception.StoreException;
import com.lunchcatch.store.repository.OwnerTermsAgreementRepository;
import com.lunchcatch.store.repository.StoreRegistrationProgressRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class OwnerTermsAgreementServiceTest {
    private final OwnerTermsAgreementRepository agreements = mock(OwnerTermsAgreementRepository.class);
    private final StoreRegistrationProgressRepository progresses = mock(StoreRegistrationProgressRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final OwnerTermsAgreementService service = new OwnerTermsAgreementService(
            agreements, new StoreRegistrationProgressService(progresses), clock);
    private StoreRegistrationProgress progress;

    @BeforeEach
    void setUp() {
        progress = StoreRegistrationProgress.start(7L);
    }

    private void prepareProgress() {
        when(progresses.findByOwnerIdForUpdate(7L)).thenReturn(Optional.of(progress));
    }

    @Test
    void 최초_동의는_서버_시각을_저장하고_STORE_INFO로_진행한다() {
        prepareProgress();
        when(agreements.findByOwnerId(7L)).thenReturn(Optional.empty());
        var row = org.mockito.ArgumentCaptor.forClass(OwnerTermsAgreement.class);

        var result = service.recordAgreements(7L);

        verify(agreements).saveAndFlush(row.capture());
        assertThat(row.getValue().getOwnerId()).isEqualTo(7L);
        assertThat(row.getValue().isRequired()).isTrue();
        assertThat(row.getValue().isAgreed()).isTrue();
        assertThat(row.getValue().getAgreedAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(row.getValue().getWithdrawnAt()).isNull();
        assertThat(result.requiredTermsCompleted()).isTrue();
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.resumeStep()).isEqualTo("STORE_INFO");
    }

    @ParameterizedTest
    @EnumSource(value = StoreRegistrationStep.class, names = {"TERMS", "STORE_INFO", "HOURS", "MENU"})
    void 기존_동의가_있으면_단계와_관계없이_수정하지_않는다(StoreRegistrationStep step) {
        prepareProgress();
        ReflectionTestUtils.setField(progress, "resumeStep", step);
        var agreedAt = LocalDateTime.now(clock).minusDays(1);
        var withdrawnAt = LocalDateTime.now(clock).minusHours(1);
        var agreement = OwnerTermsAgreement.record(7L, agreedAt);
        ReflectionTestUtils.setField(agreement, "withdrawnAt", withdrawnAt);
        when(agreements.findByOwnerId(7L)).thenReturn(Optional.of(agreement));

        assertThatThrownBy(() -> service.recordAgreements(7L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.TERMS_ALREADY_RECORDED));

        verify(agreements, never()).saveAndFlush(any());
        assertThat(agreement.getAgreedAt()).isEqualTo(agreedAt);
        assertThat(agreement.getWithdrawnAt()).isEqualTo(withdrawnAt);
        assertThat(agreement.isRequired()).isTrue();
        assertThat(agreement.isAgreed()).isTrue();
        assertThat(progress.getResumeStep()).isEqualTo(step);
        assertThat(progress.getVersion()).isZero();
    }

    @Test
    void 약관_기록이_없어도_TERMS_이후_단계에서는_저장하지_않는다() {
        prepareProgress();
        ReflectionTestUtils.setField(progress, "resumeStep", StoreRegistrationStep.STORE_INFO);
        when(agreements.findByOwnerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordAgreements(7L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_STEP_CONFLICT));

        verify(agreements, never()).saveAndFlush(any());
        assertThat(progress.getVersion()).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void 잘못된_점주_ID는_거부한다(Long ownerId) {
        assertThatThrownBy(() -> service.recordAgreements(ownerId)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(agreements, progresses);
    }

    @Test
    void 약관_UNIQUE_충돌만_전용_오류로_변환한다() {
        prepareProgress();
        when(agreements.findByOwnerId(7L)).thenReturn(Optional.empty());
        var cause = new org.hibernate.exception.ConstraintViolationException(
                "duplicate", new java.sql.SQLException("duplicate"), "uk_owner_terms_agreement_owner");
        when(agreements.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", cause));

        assertThatThrownBy(() -> service.recordAgreements(7L))
                .isInstanceOfSatisfying(StoreException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.TERMS_ALREADY_RECORDED);
                    assertThat(e.getErrorCode().getCode()).isEqualTo("TERMS-001");
                });
        assertThat(progress.getVersion()).isZero();
    }

    @Test
    void 다른_무결성_오류는_숨기지_않고_전파한다() {
        prepareProgress();
        when(agreements.findByOwnerId(7L)).thenReturn(Optional.empty());
        var failure = new DataIntegrityViolationException("not null");
        when(agreements.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.recordAgreements(7L)).isSameAs(failure);
        assertThat(progress.getVersion()).isZero();
    }
    @Test
    void 서버의_현재_진행_버전을_사용해_약관을_완료한다() {
        prepareProgress();
        ReflectionTestUtils.setField(progress, "version", 7L);
        when(agreements.findByOwnerId(7L)).thenReturn(Optional.empty());

        var result = service.recordAgreements(7L);

        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.resumeStep()).isEqualTo("STORE_INFO");
    }
}
