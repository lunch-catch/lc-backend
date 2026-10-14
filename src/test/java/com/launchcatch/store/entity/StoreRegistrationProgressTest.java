package com.launchcatch.store.entity;

import static org.assertj.core.api.Assertions.*;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class StoreRegistrationProgressTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"HOURS, IMAGES", "HOURS, MENU", "IMAGES, MENU"})
    void 완료한_단계_수정은_재개_단계와_가게를_유지하고_버전을_올린다(
            StoreRegistrationStep revisedStep, StoreRegistrationStep resumeStep) {
        var progress = atStep(resumeStep);
        long version = progress.getVersion();
        progress.reviseCompletedStep(version, revisedStep);
        assertThat(progress.getResumeStep()).isEqualTo(resumeStep);
        assertThat(progress.getStoreId()).isEqualTo(9L);
        assertThat(progress.getVersion()).isEqualTo(version + 1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(StoreRegistrationStep.class)
    void 현재_단계와_미래_단계는_완료_단계_수정으로_저장하지_못한다(StoreRegistrationStep revisedStep) {
        var progress = StoreRegistrationProgress.start(7L);
        assertThatThrownBy(() -> progress.reviseCompletedStep(0, revisedStep))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_STEP_CONFLICT));
        assertThat(progress.getVersion()).isZero();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = StoreRegistrationStep.class,
            names = {"TERMS", "STORE", "BUSINESS_VERIFICATION", "LOCATION", "MENU"})
    void 수정_정책이_없는_단계와_현재_메뉴는_완료_단계_수정에서_거부한다(StoreRegistrationStep revisedStep) {
        var progress = atStep(StoreRegistrationStep.MENU);
        assertThatThrownBy(() -> progress.reviseCompletedStep(6, revisedStep)).isInstanceOf(StoreException.class);
        assertThat(progress.getVersion()).isEqualTo(6);
    }

    @Test
    void 수정_요청의_오래된_버전과_잘못된_인자는_거부한다() {
        var progress = atStep(StoreRegistrationStep.MENU);
        assertThatThrownBy(() -> progress.reviseCompletedStep(5, StoreRegistrationStep.HOURS))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_VERSION_CONFLICT));
        assertThatIllegalArgumentException().isThrownBy(() -> progress.reviseCompletedStep(-1, StoreRegistrationStep.HOURS));
        assertThatIllegalArgumentException().isThrownBy(() -> progress.reviseCompletedStep(6, null));
        assertThat(progress.getVersion()).isEqualTo(6);
    }

    private StoreRegistrationProgress atStep(StoreRegistrationStep target) {
        var progress = StoreRegistrationProgress.start(7L);
        while (progress.getResumeStep() != target) {
            var step = progress.getResumeStep();
            progress.completeStep(progress.getVersion(), step, step == StoreRegistrationStep.STORE ? 9L : null);
        }
        return progress;
    }

    @Test
    void 초기_단계는_약관이며_가게는_없고_버전은_0이다() {
        var progress = StoreRegistrationProgress.start(7L);
        assertThat(progress.getOwnerId()).isEqualTo(7L);
        assertThat(progress.getStoreId()).isNull();
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.TERMS);
        assertThat(progress.getVersion()).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 유효하지_않은_점주로_생성하지_않는다(Long ownerId) {
        assertThatIllegalArgumentException().isThrownBy(() -> StoreRegistrationProgress.start(ownerId));
    }

    @Test
    void 단계별_저장_후_다음_단계로_진행하며_메뉴_저장도_버전을_올린다() {
        var progress = StoreRegistrationProgress.start(7L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        progress.completeStep(1, StoreRegistrationStep.STORE, 9L);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.BUSINESS_VERIFICATION);
        progress.completeStep(2, StoreRegistrationStep.BUSINESS_VERIFICATION, null);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.LOCATION);
        progress.completeStep(3, StoreRegistrationStep.LOCATION, null);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.HOURS);
        progress.completeStep(4, StoreRegistrationStep.HOURS, null);
        progress.completeStep(5, StoreRegistrationStep.IMAGES, null);
        progress.completeStep(6, StoreRegistrationStep.MENU, null);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.MENU);
        assertThat(progress.getStoreId()).isEqualTo(9L);
        assertThat(progress.getVersion()).isEqualTo(7);
        progress.completeStep(7, StoreRegistrationStep.MENU, null);
        assertThat(progress.getVersion()).isEqualTo(8);
    }

    @Test
    void 사업자_검증을_건너뛰고_위치를_저장하지_못한다() {
        var progress = StoreRegistrationProgress.start(7L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        progress.completeStep(1, StoreRegistrationStep.STORE, 9L);
        assertThatThrownBy(() -> progress.completeStep(2, StoreRegistrationStep.LOCATION, null))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_STEP_CONFLICT));
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.BUSINESS_VERIFICATION);
        assertThat(progress.getVersion()).isEqualTo(2);
        assertThat(progress.getStoreId()).isEqualTo(9L);
    }

    @Test
    void 오래된_버전은_진행_상태를_변경하지_않는다() {
        var progress = StoreRegistrationProgress.start(7L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        assertThatThrownBy(() -> progress.completeStep(0, StoreRegistrationStep.STORE, 9L))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_VERSION_CONFLICT));
        assertThat(progress.getVersion()).isEqualTo(1);
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.STORE);
        assertThat(progress.getStoreId()).isNull();
    }

    @Test
    void 단계_건너뛰기와_뒤로_진행은_거부한다() {
        var progress = StoreRegistrationProgress.start(7L);
        assertThatThrownBy(() -> progress.completeStep(0, StoreRegistrationStep.HOURS, null))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_STEP_CONFLICT));
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        assertThatThrownBy(() -> progress.completeStep(1, StoreRegistrationStep.TERMS, null))
                .isInstanceOf(StoreException.class);
        assertThat(progress.getVersion()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 기본_정보_완료에는_유효한_가게가_필요하다(Long storeId) {
        var progress = StoreRegistrationProgress.start(7L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        assertThatIllegalArgumentException().isThrownBy(() ->
                progress.completeStep(1, StoreRegistrationStep.STORE, storeId));
        assertThat(progress.getResumeStep()).isEqualTo(StoreRegistrationStep.STORE);
        assertThat(progress.getVersion()).isEqualTo(1);
    }

    @Test
    void 기본_정보_외_단계에서_가게를_연결하지_못한다() {
        var progress = StoreRegistrationProgress.start(7L);
        assertThatIllegalArgumentException().isThrownBy(() ->
                progress.completeStep(0, StoreRegistrationStep.TERMS, 9L));
        assertThat(progress.getVersion()).isZero();
    }

    @Test
    void 음수_버전과_없는_단계를_거부한다() {
        var progress = StoreRegistrationProgress.start(7L);
        assertThatIllegalArgumentException().isThrownBy(() ->
                progress.completeStep(-1, StoreRegistrationStep.TERMS, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                progress.completeStep(0, null, null));
    }
}
