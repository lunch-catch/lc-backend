package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.StoreLocationRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.StoreLocationSaveResult;
import com.launchcatch.store.contract.StoreLocationSaver;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OwnerStoreLocationServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final StoreLocationSaver locations = mock(StoreLocationSaver.class);
    private final OwnerStoreLocationService service = new OwnerStoreLocationService(owners, locations);
    private final StoreLocationRequest request = new StoreLocationRequest(2L, "123", "주소", BigDecimal.ZERO, BigDecimal.ZERO);

    @Test
    void 입점_신청_중인_점주의_확정_위치를_저장한다() {
        var owner = mock(Owner.class);
        when(owner.getStatus()).thenReturn(OwnerStatus.ONBOARDING);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        when(locations.save(7L, request.toCommand())).thenReturn(new StoreLocationSaveResult(9L, "HOURS", 3));
        var result = service.save(7L, request);
        assertThat(result.storeId()).isEqualTo(9L);
        assertThat(result.resumeStep()).isEqualTo("HOURS");
        assertThat(result.version()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ACTIVE", "SUSPENDED", "WITHDRAWN"})
    void 다른_점주_상태는_저장할_수_없다(OwnerStatus status) {
        var owner = mock(Owner.class);
        when(owner.getStatus()).thenReturn(status);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        assertThatThrownBy(() -> service.save(7L, request)).isInstanceOf(OwnerException.class);
        verifyNoInteractions(locations);
    }

    @Test
    void 없는_계정은_저장하지_않는다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save(7L, request)).isInstanceOf(AuthException.class);
        verifyNoInteractions(locations);
    }
}
