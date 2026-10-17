package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.store.contract.OwnerStoreSnapshot;
import com.launchcatch.store.contract.OwnerStoreSnapshot.Image;
import com.launchcatch.store.repository.OwnerStoreQueryRepository;
import com.launchcatch.store.repository.StoreQueryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OwnerStoreQueryServiceTest {
    private final OwnerStoreQueryRepository stores = mock(OwnerStoreQueryRepository.class);
    private final StoreQueryServiceImpl service = new StoreQueryServiceImpl(mock(StoreQueryRepository.class), stores, "https://cdn.example.com");

    private void draft() {
        when(stores.findByOwnerId(1L)).thenReturn(Optional.of(new OwnerStoreSnapshot(
                10L, false, "식당", "KOREAN", "점주", "1234567890", "02-123-4567", "VERIFIED", null,
                null, null, null, null, List.of(), null, List.of(), List.of())));
    }

    @Test
    void 조회_장애를_가게_없음으로_숨기지_않는다() {
        when(stores.findByOwnerId(1L)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> service.findOwnerStore(1L)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void 이미지가_있는데_CDN_설정이_없으면_오류다() {
        draft();
        when(stores.findImages(10L)).thenReturn(List.of(new Image("logo.jpg", null, null)));
        assertThatThrownBy(() -> new StoreQueryServiceImpl(mock(StoreQueryRepository.class), stores, "").findOwnerStore(1L))
                .isInstanceOf(IllegalStateException.class);
    }
}
