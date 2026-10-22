package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.StoreQueryService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OwnerStoreServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final StoreQueryService stores = mock(StoreQueryService.class);
    private final OwnerStoreService service = new OwnerStoreService(owners, stores);

    @Test
    void 없는_계정은_인증_오류다() {
        when(owners.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMyStore(1L)).isInstanceOf(AuthException.class);
    }
}
