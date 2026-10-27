package com.lunchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.owner.dto.StoreCompleteRequest;
import com.lunchcatch.owner.entity.Owner;
import com.lunchcatch.owner.exception.OwnerException;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.store.contract.StoreCompleter;
import com.lunchcatch.store.contract.StoreCompleteResult;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OwnerStoreCompleteServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final StoreCompleter stores = mock(StoreCompleter.class);
    private final OwnerStoreCompleteService service = new OwnerStoreCompleteService(owners, stores);

    @Test
    void ONBOARDING_점주는_본인_ID와_현재_버전으로_최종_등록한다() {
        var owner = Owner.create("test@example.com", "hash");
        when(owners.findByIdForLogin(1L)).thenReturn(Optional.of(owner));
        when(stores.complete(1L, 5)).thenReturn(
                new StoreCompleteResult(10L, LocalDateTime.of(2026, 10, 19, 10, 0), 6));

        var result = service.complete(1L, new StoreCompleteRequest(5L));

        assertThat(result.storeId()).isEqualTo(10L);
        assertThat(result.finalizedAt()).isEqualTo(OffsetDateTime.parse("2026-10-19T10:00:00+09:00"));
    }

    @Test
    void ACTIVE_점주는_최종_등록을_다시_요청할_수_없다() {
        var owner = Owner.create("test@example.com", "hash");
        owner.activate();
        when(owners.findByIdForLogin(1L)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.complete(1L, new StoreCompleteRequest(5L)))
                .isInstanceOf(OwnerException.class);
        verifyNoInteractions(stores);
    }

    @Test
    void 없는_계정은_등록할_수_없다() {
        assertThatThrownBy(() -> service.complete(1L, new StoreCompleteRequest(5L))).isInstanceOf(AuthException.class);
        verifyNoInteractions(stores);
    }
}
