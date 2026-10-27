package com.lunchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.exception.OwnerErrorCode;
import com.lunchcatch.owner.exception.OwnerException;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.store.contract.StoreOnboardingQuery;
import com.lunchcatch.store.contract.StoreOnboardingStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

class OwnerStoreOnboardingServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final StoreOnboardingQuery stores = mock(StoreOnboardingQuery.class);
    private final OwnerStoreOnboardingService service = new OwnerStoreOnboardingService(owners, stores);

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ONBOARDING", "ACTIVE"})
    void 입점_신청과_운영_점주는_본인의_진행_상태를_조회한다(OwnerStatus state) {
        when(owners.findStatusById(7L)).thenReturn(Optional.of(state));
        when(stores.getProgress(7L)).thenReturn(new StoreOnboardingStatus(20L, "LOCATION", 2,
                false, false, List.of(new StoreOnboardingStatus.Step("TERMS", true, true))));
        var response = service.getProgress(7L);
        assertThat(response.storeId()).isEqualTo(20L);
        assertThat(response.resumeStep()).isEqualTo("LOCATION");
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.finalized()).isFalse();
        assertThat(response.canFinalize()).isFalse();
        assertThat(response.steps().getFirst().step()).isEqualTo("TERMS");
        assertThat(response.steps().getFirst().required()).isTrue();
        assertThat(response.steps().getFirst().completed()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void 정지와_탈퇴_상태는_조회할_수_없다(OwnerStatus state) {
        when(owners.findStatusById(7L)).thenReturn(Optional.of(state));
        assertThatThrownBy(() -> service.getProgress(7L)).isInstanceOfSatisfying(OwnerException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(OwnerErrorCode.STATUS_NOT_ALLOWED));
        verifyNoInteractions(stores);
    }

    @Test
    void 계정이_없으면_인증_오류이고_DB_장애는_그대로_전파한다() {
        when(owners.findStatusById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProgress(7L)).isInstanceOf(AuthException.class);
        when(owners.findStatusById(7L)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> service.getProgress(7L)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(stores);
    }
}
