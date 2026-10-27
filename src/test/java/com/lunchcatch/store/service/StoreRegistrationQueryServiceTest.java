package com.lunchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lunchcatch.store.entity.StoreRegistrationProgress;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.repository.StoreRegistrationProgressRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StoreRegistrationQueryServiceTest {
    private final StoreRegistrationProgressRepository progresses = mock(StoreRegistrationProgressRepository.class);
    private final StoreRegistrationQueryService service = new StoreRegistrationQueryService(progresses);

    @Test
    void 진행_상태가_없으면_약관부터_시작한다() {
        when(progresses.findByOwnerId(7L)).thenReturn(Optional.empty());
        assertThat(service.findResumeStep(7L)).isEqualTo("TERMS");
    }

    @ParameterizedTest
    @EnumSource(StoreRegistrationStep.class)
    void 저장된_현재_단계만_반환한다(StoreRegistrationStep step) {
        var progress = StoreRegistrationProgress.start(7L);
        while (progress.getResumeStep() != step) {
            var current = progress.getResumeStep();
            progress.completeStep(progress.getVersion(), current,
                    current == StoreRegistrationStep.STORE_INFO ? 9L : null);
        }
        when(progresses.findByOwnerId(7L)).thenReturn(Optional.of(progress));
        assertThat(service.findResumeStep(7L)).isEqualTo(step.name());
        assertThat(progress.getResumeStep()).isEqualTo(step);
    }
}