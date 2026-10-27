package com.lunchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunchcatch.store.StoreOnboardingReadiness;
import com.lunchcatch.store.dto.StoreOnboardingQueryRow;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.repository.StoreOnboardingQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

class StoreRegistrationProgressQueryServiceTest {
    private final StoreOnboardingQueryRepository stores = mock(StoreOnboardingQueryRepository.class);
    private final StoreRegistrationProgressQueryService service = new StoreRegistrationProgressQueryService(stores);

    @Test
    void 최초_진입은_여섯_단계_미완료와_TERMS를_반환한다() {
        when(stores.findByOwnerId(7L)).thenReturn(new StoreOnboardingQueryRow(null, 0, false,
                new StoreOnboardingReadiness(false, false, false, false, false, 0, true)));
        var response = service.getProgress(7L);
        assertThat(response.storeId()).isNull();
        assertThat(response.version()).isZero();
        assertThat(response.resumeStep()).isEqualTo("TERMS");
        assertThat(response.canFinalize()).isFalse();
        assertThat(response.finalized()).isFalse();
        assertThat(response.steps()).extracting(step -> step.step())
                .containsExactly("TERMS", "STORE_INFO", "LOCATION", "HOURS", "IMAGES", "MENU");
        assertThat(response.steps()).allMatch(step -> !step.completed());
        assertThat(response.steps()).filteredOn(step -> !step.required()).extracting(step -> step.step())
                .containsExactly("MENU");
    }

    @Test
    void 필수_단계가_완료되면_메뉴가_없어도_최종_등록이_가능하다() {
        when(stores.findByOwnerId(7L)).thenReturn(new StoreOnboardingQueryRow(20L, 5, false,
                new StoreOnboardingReadiness(true, true, true, true, true, 0, true)));
        var response = service.getProgress(7L);
        assertThat(response.storeId()).isEqualTo(20L);
        assertThat(response.version()).isEqualTo(5);
        assertThat(response.resumeStep()).isEqualTo("MENU");
        assertThat(response.canFinalize()).isTrue();
        assertThat(response.steps().getLast().completed()).isFalse();
    }

    @Test
    void 최종_등록된_가게는_재개하지_않는다() {
        when(stores.findByOwnerId(7L)).thenReturn(new StoreOnboardingQueryRow(20L, 6, true,
                new StoreOnboardingReadiness(true, true, true, true, true, 1, true)));
        var response = service.getProgress(7L);
        assertThat(response.finalized()).isTrue();
        assertThat(response.resumeStep()).isNull();
        assertThat(response.canFinalize()).isFalse();
        assertThat(response.steps()).allMatch(step -> step.completed());
    }

    @Test
    void 잘못된_점주_ID와_DB_장애를_최초_진입으로_숨기지_않는다() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.getProgress(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.getProgress(0L));
        when(stores.findByOwnerId(7L)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> service.getProgress(7L)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @ParameterizedTest
    @EnumSource(value = StoreRegistrationStep.class, names = "MENU", mode = EnumSource.Mode.EXCLUDE)
    void 먼저_누락된_필수_단계로_안내하고_최종_등록을_막는다(StoreRegistrationStep missing) {
        when(stores.findByOwnerId(7L)).thenReturn(new StoreOnboardingQueryRow(20L, 5, false,
                new StoreOnboardingReadiness(missing != StoreRegistrationStep.TERMS,
                        missing != StoreRegistrationStep.STORE_INFO, missing != StoreRegistrationStep.LOCATION,
                        missing != StoreRegistrationStep.HOURS, missing != StoreRegistrationStep.IMAGES, 1, true)));

        var response = service.getProgress(7L);

        assertThat(response.resumeStep()).isEqualTo(missing.name());
        assertThat(response.canFinalize()).isFalse();
        assertThat(response.steps()).filteredOn(step -> step.step().equals(missing.name()))
                .extracting(step -> step.completed()).containsExactly(false);
    }

    @Test
    void 메뉴가_있는데_필수_항목이_없으면_최종_등록을_막는다() {
        when(stores.findByOwnerId(7L)).thenReturn(new StoreOnboardingQueryRow(20L, 5, false,
                new StoreOnboardingReadiness(true, true, true, true, true, 1, false)));

        var response = service.getProgress(7L);

        assertThat(response.resumeStep()).isEqualTo("MENU");
        assertThat(response.canFinalize()).isFalse();
        assertThat(response.steps().getLast().completed()).isFalse();
    }

    @Test
    void 메뉴까지_완료해도_최종_등록_전에는_MENU를_유지한다() {
        when(stores.findByOwnerId(7L)).thenReturn(new StoreOnboardingQueryRow(20L, 5, false,
                new StoreOnboardingReadiness(true, true, true, true, true, 3, true)));

        var response = service.getProgress(7L);

        assertThat(response.resumeStep()).isEqualTo("MENU");
        assertThat(response.canFinalize()).isTrue();
        assertThat(response.steps().getLast().completed()).isTrue();
    }
}
