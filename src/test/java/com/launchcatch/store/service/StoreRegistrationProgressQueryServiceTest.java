package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.dto.StoreRegistrationProgressSnapshot;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreRegistrationProgressQueryServiceTest {

    private static final Long OWNER_ID = 7L;

    @Mock private StoreRegistrationProgressService progressService;
    @Mock private OwnerStatusQuery ownerStatusQuery;

    private StoreRegistrationProgressQueryService service;

    @BeforeEach
    void setUp() {
        service = new StoreRegistrationProgressQueryService(progressService, ownerStatusQuery);
    }

    @Test
    void ONBOARDING_점주는_진행_상태를_조회한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));
        when(progressService.initialize(OWNER_ID))
                .thenReturn(new StoreRegistrationProgressSnapshot(20L, StoreRegistrationStep.IMAGES, 3L));

        StoreRegistrationProgressResponse response = service.getProgress(OWNER_ID);

        assertThat(response.storeId()).isEqualTo(20L);
        assertThat(response.resumeStep()).isEqualTo(StoreRegistrationStep.IMAGES);
        assertThat(response.version()).isEqualTo(3L);
    }

    @Test
    void ONBOARDING이_아니면_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));

        assertThatThrownBy(() -> service.getProgress(OWNER_ID))
                .isInstanceOfSatisfying(StoreException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.REGISTRATION_STEP_CONFLICT));
    }
}
