package com.launchcatch.store.entity;

import static org.assertj.core.api.Assertions.*;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class StoreRegistrationProgressTest {
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
        progress.completeStep(2, StoreRegistrationStep.LOCATION, null);
        progress.completeStep(3, StoreRegistrationStep.BUSINESS_VERIFICATION, null);
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
