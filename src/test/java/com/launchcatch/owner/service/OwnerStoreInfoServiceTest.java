package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.StoreInfoRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.StoreInfoSaver;
import com.launchcatch.store.contract.StoreInfoSaveResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

class OwnerStoreInfoServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final StoreInfoSaver stores = mock(StoreInfoSaver.class);
    private final OwnerStoreInfoService service = new OwnerStoreInfoService(owners, stores);
    private final StoreInfoRequest request = new StoreInfoRequest(1L, "가게", "KOREAN", "대표", "1234567890", "02-123-4567");

    private void onboardingOwner() {
        var owner = mock(Owner.class);
        when(owner.getStatus()).thenReturn(OwnerStatus.ONBOARDING);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
    }

    @Test
    void 입점_신청_점주의_입력을_가게_저장_계약에_전달하고_결과를_반환한다() {
        onboardingOwner();
        when(stores.save(7L, request.toCommand())).thenReturn(new StoreInfoSaveResult(9L, "LOCATION", 2, "VERIFIED"));
        var result = service.save(7L, request);
        assertThat(result.storeId()).isEqualTo(9L);
        assertThat(result.resumeStep()).isEqualTo("LOCATION");
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.verificationStatus()).isEqualTo("VERIFIED");
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ACTIVE", "SUSPENDED", "WITHDRAWN"})
    void 입점_신청_상태가_아니면_가게_저장을_호출하지_않는다(OwnerStatus status) {
        var owner = mock(Owner.class);
        when(owner.getStatus()).thenReturn(status);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        assertThatThrownBy(() -> service.save(7L, request)).isInstanceOf(OwnerException.class);
        verifyNoInteractions(stores);
    }

    @Test
    void 계정_부재와_조회_장애는_가게_저장으로_넘기지_않는다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save(7L, request)).isInstanceOf(AuthException.class);
        when(owners.findByIdForLogin(7L)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> service.save(7L, request)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(stores);
    }
}
