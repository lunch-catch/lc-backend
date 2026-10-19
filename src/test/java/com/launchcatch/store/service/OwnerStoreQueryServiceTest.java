package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.store.contract.OwnerStoreOverview.TermsAgreement;
import com.launchcatch.store.contract.OwnerStoreSnapshot;
import com.launchcatch.store.dto.StoreRegistrationProgressSnapshot;
import com.launchcatch.store.entity.StoreRegistrationStep;
import java.time.LocalDateTime;
import com.launchcatch.store.contract.OwnerStoreSnapshot.Image;
import com.launchcatch.store.repository.OwnerStoreQueryRepository;
import com.launchcatch.store.repository.StoreOnboardingQueryRepository;
import com.launchcatch.store.repository.StoreQueryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.launchcatch.store.StoreOnboardingReadiness;
import com.launchcatch.store.dto.StoreOnboardingQueryRow;
import org.springframework.dao.DataAccessResourceFailureException;

class OwnerStoreQueryServiceTest {
    private final StoreOnboardingQueryRepository readinessQuery = mock(StoreOnboardingQueryRepository.class);
    private final OwnerStoreQueryRepository stores = mock(OwnerStoreQueryRepository.class);
    private final StoreQueryServiceImpl service = new StoreQueryServiceImpl(mock(StoreQueryRepository.class), stores, readinessQuery, "https://cdn.example.com");

    @BeforeEach
    void readiness() {
        when(readinessQuery.findByOwnerId(1L)).thenReturn(new StoreOnboardingQueryRow(
                null, 0, false, new StoreOnboardingReadiness(false, false, false, false, false, 0, true)));
    }

    private void draft() {
        when(stores.findByOwnerId(1L)).thenReturn(Optional.of(new OwnerStoreSnapshot(
                10L, false, "식당", "KOREAN", "점주", "1234567890", "02-123-4567", "VERIFIED", null,
                null, null, null, null, List.of(), null, List.of(), List.of())));
    }

    @Test
    void 조회_장애를_가게_없음으로_숨기지_않는다() {
        when(stores.findByOwnerId(1L)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> service.findOwnerStore(1L)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void 이미지가_있는데_CDN_설정이_없으면_오류다() {
        draft();
        when(stores.findImages(10L)).thenReturn(List.of(new Image("logo.jpg", null, null)));
        assertThatThrownBy(() -> new StoreQueryServiceImpl(mock(StoreQueryRepository.class), stores, readinessQuery, "").findOwnerStore(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 최종_등록된_가게는_저장된_단계와_무관하게_온보딩을_재개하지_않는다() {
        when(stores.findByOwnerId(1L)).thenReturn(Optional.of(store(true, "VERIFIED")));
        when(stores.findProgress(1L)).thenReturn(Optional.of(
                new StoreRegistrationProgressSnapshot(10L, StoreRegistrationStep.MENU, 6L)));

        var onboarding = service.getOwnerStoreOverview(1L).onboarding();

        assertThat(onboarding.currentStep()).isNull();
        assertThat(onboarding.resumeStep()).isNull();
        assertThat(onboarding.canComplete()).isFalse();
    }

    @Test
    void 동의한_약관도_철회_시각이_있으면_TERMS는_미완료다() {
        var agreedAt = LocalDateTime.of(2026, 10, 17, 10, 0);
        when(stores.findTermsAgreement(1L)).thenReturn(Optional.of(new TermsAgreement(
                null, null, true, true, agreedAt, agreedAt, agreedAt.plusHours(1))));

        var onboarding = service.getOwnerStoreOverview(1L).onboarding();

        assertThat(onboarding.steps().getFirst().completed()).isFalse();
        assertThat(onboarding.resumeStep()).isEqualTo("TERMS");
        assertThat(onboarding.canComplete()).isFalse();
    }

    @Test
    void 기본_정보가_있어도_사업자_검증이_FAILED면_STORE_INFO는_미완료다() {
        var agreedAt = LocalDateTime.of(2026, 10, 17, 10, 0);
        when(stores.findByOwnerId(1L)).thenReturn(Optional.of(store(false, "FAILED")));
        when(stores.findTermsAgreement(1L)).thenReturn(Optional.of(new TermsAgreement(
                null, null, true, true, agreedAt, agreedAt, null)));
        when(stores.findProgress(1L)).thenReturn(Optional.of(
                new StoreRegistrationProgressSnapshot(10L, StoreRegistrationStep.LOCATION, 2L)));

        when(readinessQuery.findByOwnerId(1L)).thenReturn(new StoreOnboardingQueryRow(
                10L, 2, false, new StoreOnboardingReadiness(true, false, false, false, false, 0, true)));
        var onboarding = service.getOwnerStoreOverview(1L).onboarding();

        assertThat(onboarding.steps().get(1).completed()).isFalse();
        assertThat(onboarding.resumeStep()).isEqualTo("STORE_INFO");
        assertThat(onboarding.canComplete()).isFalse();
    }

    @Test
    void 필수_데이터가_있어도_이미지_단계를_완료해야_최종_등록할_수_있다() {
        draft();
        when(readinessQuery.findByOwnerId(1L)).thenReturn(new StoreOnboardingQueryRow(
                10L, 5, false, new StoreOnboardingReadiness(true, true, true, true, true, 0, true)));
        when(stores.findProgress(1L)).thenReturn(Optional.of(
                new StoreRegistrationProgressSnapshot(10L, StoreRegistrationStep.IMAGES, 5L)));

        assertThat(service.getOwnerStoreOverview(1L).onboarding().canComplete()).isFalse();

        when(stores.findProgress(1L)).thenReturn(Optional.of(
                new StoreRegistrationProgressSnapshot(10L, StoreRegistrationStep.MENU, 6L)));

        assertThat(service.getOwnerStoreOverview(1L).onboarding().canComplete()).isTrue();
    }

    private OwnerStoreSnapshot store(boolean finalized, String verificationStatus) {
        return new OwnerStoreSnapshot(10L, finalized, "식당", "KOREAN", "점주", "1234567890", "02-123-4567",
                verificationStatus, LocalDateTime.of(2026, 10, 17, 10, 0),
                null, null, null, null, List.of(), null, List.of(), List.of());
    }
}
