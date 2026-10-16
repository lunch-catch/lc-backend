package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.dto.StoreRegistrationProgressSnapshot;
import com.launchcatch.store.entity.StoreRegistrationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreRegistrationProgressQueryServiceTest {

    private static final Long OWNER_ID = 7L;

    @Mock private StoreRegistrationProgressService progressService;

    private StoreRegistrationProgressQueryService service;

    @BeforeEach
    void setUp() {
        service = new StoreRegistrationProgressQueryService(progressService);
    }

    @Test
    void 점주는_진행_상태를_조회한다() {
        when(progressService.initialize(OWNER_ID))
                .thenReturn(new StoreRegistrationProgressSnapshot(20L, StoreRegistrationStep.IMAGES, 3L));

        StoreRegistrationProgressResponse response = service.getProgress(OWNER_ID);

        assertThat(response.storeId()).isEqualTo(20L);
        assertThat(response.resumeStep()).isEqualTo(StoreRegistrationStep.IMAGES);
        assertThat(response.version()).isEqualTo(3L);
    }
}
