package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.owner.repository.OwnerStatusCacheRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OwnerStatusServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final OwnerStatusCacheRepository cache = mock(OwnerStatusCacheRepository.class);
    private final OwnerStatusService service = new OwnerStatusService(owners, cache);

    @AfterEach
    void cleanup() { TransactionSynchronizationManager.clear(); }

    @Test
    void 캐시가_있으면_DB를_조회하지_않는다() {
        when(cache.find(7L)).thenReturn(Optional.of("ACTIVE"));
        assertThat(service.findStatus(7L)).contains(OwnerAccountStatus.ACTIVE);
        verifyNoInteractions(owners);
    }

    @Test
    void 캐시가_없으면_DB_상태를_저장한다() {
        when(cache.find(7L)).thenReturn(Optional.empty());
        when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.SUSPENDED));
        assertThat(service.findStatus(7L)).contains(OwnerAccountStatus.SUSPENDED);
        verify(cache).save(7L, "SUSPENDED");
    }

    @Test
    void Redis_읽기와_쓰기_장애에도_DB_결과를_반환한다() {
        when(cache.find(7L)).thenThrow(new DataAccessResourceFailureException("offline"));
        when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.ACTIVE));
        doThrow(new DataAccessResourceFailureException("offline")).when(cache).save(7L, "ACTIVE");
        assertThat(service.findStatus(7L)).contains(OwnerAccountStatus.ACTIVE);
    }

    @Test
    void 잘못된_캐시값은_DB_상태로_교체한다() {
        when(cache.find(7L)).thenReturn(Optional.of("INVALID"));
        when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.WITHDRAWN));
        assertThat(service.findStatus(7L)).contains(OwnerAccountStatus.WITHDRAWN);
        verify(cache).save(7L, "WITHDRAWN");
    }

    @Test
    void 없는_점주는_캐시에_저장하지_않는다() {
        when(cache.find(7L)).thenReturn(Optional.empty());
        when(owners.findStatusById(7L)).thenReturn(Optional.empty());
        assertThat(service.findStatus(7L)).isEmpty();
        verify(cache, never()).save(anyLong(), anyString());
    }

    @Test
    void 직접_조회는_캐시를_우회한다() {
        when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.SUSPENDED));
        assertThat(service.findCurrentStatus(7L)).contains(OwnerAccountStatus.SUSPENDED);
        verifyNoInteractions(cache);
    }

    @Test
    void 트랜잭션_내_조회는_미커밋_상태를_캐시에_게시하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.ACTIVE));
        assertThat(service.findStatus(7L)).contains(OwnerAccountStatus.ACTIVE);
        verifyNoInteractions(cache);
    }

    @Test
    void 무효화는_커밋_이후에만_실행된다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        service.invalidateAfterCommit(7L);
        verifyNoInteractions(cache);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(cache).delete(7L);
    }

    @Test
    void 롤백되면_캐시를_삭제하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        service.invalidateAfterCommit(7L);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
        verifyNoInteractions(cache);
    }

    @Test
    void 커밋_후_캐시_삭제_실패는_전파하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new DataAccessResourceFailureException("offline")).when(cache).delete(7L);
        service.invalidateAfterCommit(7L);
        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCommit())).doesNotThrowAnyException();
    }

    @Test
    void 트랜잭션_없는_무효화는_거부한다() {
        assertThatThrownBy(() -> service.invalidateAfterCommit(7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void DB_장애를_정상_상태로_처리하지_않는다() {
        when(cache.find(7L)).thenReturn(Optional.empty());
        when(owners.findStatusById(7L)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> service.findStatus(7L)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
