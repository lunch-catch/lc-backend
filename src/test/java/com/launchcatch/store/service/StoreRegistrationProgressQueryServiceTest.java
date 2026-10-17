package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;
import com.launchcatch.store.entity.StoreRegistrationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreRegistrationProgressQueryServiceTest {

    private static final Long OWNER_ID = 7L;

    @Mock private StoreRegistrationProgressRepository progresses;

    private StoreRegistrationProgressQueryService service;

    @BeforeEach
    void setUp() {
        service = new StoreRegistrationProgressQueryService(progresses);
    }

    @Test
    void 진행_상태가_없으면_최초_단계와_버전_0을_반환한다() {
        when(progresses.findByOwnerId(OWNER_ID)).thenReturn(Optional.empty());

        var response = service.getProgress(OWNER_ID);

        assertThat(response.storeId()).isNull();
        assertThat(response.resumeStep()).isEqualTo(StoreRegistrationStep.TERMS);
        assertThat(response.version()).isZero();
    }

    @Test
    void 점주는_진행_상태를_조회한다() {
        var progress = StoreRegistrationProgress.start(OWNER_ID);
        ReflectionTestUtils.setField(progress, "storeId", 20L);
        ReflectionTestUtils.setField(progress, "resumeStep", StoreRegistrationStep.IMAGES);
        ReflectionTestUtils.setField(progress, "version", 3L);
        when(progresses.findByOwnerId(OWNER_ID)).thenReturn(Optional.of(progress));

        StoreRegistrationProgressResponse response = service.getProgress(OWNER_ID);

        assertThat(response.storeId()).isEqualTo(20L);
        assertThat(response.resumeStep()).isEqualTo(StoreRegistrationStep.IMAGES);
        assertThat(response.version()).isEqualTo(3L);
    }

}
