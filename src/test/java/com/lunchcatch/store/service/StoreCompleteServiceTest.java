package com.lunchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunchcatch.store.StoreBasicInfo;
import com.lunchcatch.store.StoreOnboardingReadiness;
import com.lunchcatch.store.contract.StoreFinalizedEvent;
import com.lunchcatch.store.dto.StoreOnboardingQueryRow;
import com.lunchcatch.store.entity.Store;
import com.lunchcatch.store.entity.StoreRegistrationProgress;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.exception.StoreErrorCode;
import com.lunchcatch.store.exception.StoreException;
import com.lunchcatch.store.repository.StoreOnboardingQueryRepository;
import com.lunchcatch.store.repository.StoreRegistrationProgressRepository;
import com.lunchcatch.store.repository.StoreRepository;
import java.time.*;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class StoreCompleteServiceTest {
    private final StoreRegistrationProgressRepository progresses = mock(StoreRegistrationProgressRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final StoreOnboardingQueryRepository onboarding = mock(StoreOnboardingQueryRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final StoreCompleteService service = new StoreCompleteService(progresses, stores, onboarding, events, clock);

    private StoreRegistrationProgress progress() {
        var progress = StoreRegistrationProgress.start(1L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        progress.completeStep(1, StoreRegistrationStep.STORE_INFO, 10L);
        progress.completeStep(2, StoreRegistrationStep.LOCATION, null);
        progress.completeStep(3, StoreRegistrationStep.HOURS, null);
        progress.completeStep(4, StoreRegistrationStep.IMAGES, null);
        when(progresses.findByOwnerIdForUpdate(1L)).thenReturn(Optional.of(progress));
        return progress;
    }

    private Store draft() {
        var store = Store.createDraft(1L, new StoreBasicInfo("식당", "KOREAN", "점주", "1234567890", "02-123-4567"), LocalDateTime.now(clock));
        ReflectionTestUtils.setField(store, "id", 10L);
        when(stores.findByOwnerId(1L)).thenReturn(Optional.of(store));
        return store;
    }

    @Test
    void 메뉴_단계_저장_없이도_메뉴_0개인_가게를_확정한다() {
        var progress = progress();
        var store = draft();
        when(onboarding.findByOwnerId(1L)).thenReturn(new StoreOnboardingQueryRow(10L, 5, false,
                new StoreOnboardingReadiness(true, true, true, true, true, 0, true)));

        var result = service.complete(1L, 5);

        assertThat(result.storeId()).isEqualTo(10L);
        assertThat(result.finalizedAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(store.isFinalized()).isTrue();
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.MENU);
        assertThat(result.version()).isEqualTo(6);
        verify(events).publishEvent(new StoreFinalizedEvent(1L, 10L));
    }

    static Stream<StoreOnboardingReadiness> incomplete() {
        return Stream.of(
                new StoreOnboardingReadiness(false, true, true, true, true, 0, true),
                new StoreOnboardingReadiness(true, true, true, true, false, 0, true),
                new StoreOnboardingReadiness(true, true, true, true, true, 1, false));
    }

    @ParameterizedTest
    @MethodSource("incomplete")
    void 실제_필수_데이터가_미완성이면_MENU여도_거절한다(StoreOnboardingReadiness readiness) {
        var progress = progress();
        var store = draft();
        when(onboarding.findByOwnerId(1L)).thenReturn(new StoreOnboardingQueryRow(10L, 5, false, readiness));
        assertThatThrownBy(() -> service.complete(1L, 5)).isInstanceOfSatisfying(StoreException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_REQUIREMENTS_NOT_MET));
        assertThat(store.isFinalized()).isFalse();
        assertThat(progress.getVersion()).isEqualTo(5);
        verifyNoInteractions(events);
    }

    @Test
    void 오래된_버전은_가게를_변경하지_않는다() {
        progress();
        assertThatThrownBy(() -> service.complete(1L, 4)).isInstanceOfSatisfying(StoreException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_VERSION_CONFLICT));
        verifyNoInteractions(stores, events);
    }

    @Test
    void 다른_가게에_연결된_진행_상태는_거절한다() {
        var progress = progress();
        ReflectionTestUtils.setField(progress, "storeId", 11L);
        var store = draft();
        assertThatThrownBy(() -> service.complete(1L, 5)).isInstanceOf(StoreException.class);
        assertThat(store.isFinalized()).isFalse();
        verifyNoInteractions(events);
    }

    @Test
    void 이미_확정한_가게는_확정_시각을_덮어쓰지_않는다() {
        progress();
        var store = draft();
        var before = LocalDateTime.now(clock).minusDays(1);
        store.completeRegistration(before);
        assertThatThrownBy(() -> service.complete(1L, 5)).isInstanceOf(StoreException.class);
        assertThat(store.getFinalizedAt()).isEqualTo(before);
    }

    @Test
    void 동기_이벤트_실패를_성공으로_숨기지_않는다() {
        progress();
        draft();
        when(onboarding.findByOwnerId(1L)).thenReturn(new StoreOnboardingQueryRow(10L, 5, false,
                new StoreOnboardingReadiness(true, true, true, true, true, 0, true)));
        doThrow(new IllegalStateException("활성화 실패")).when(events).publishEvent(any(Object.class));
        assertThatThrownBy(() -> service.complete(1L, 5)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 진행_상태가_없으면_거절한다() {
        assertThatThrownBy(() -> service.complete(1L, 0)).isInstanceOf(StoreException.class);
        verifyNoInteractions(events);
    }
}
