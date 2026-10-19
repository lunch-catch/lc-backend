package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.owner.repository.OwnerStatusCacheRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OwnerStatusCacheInvalidationServiceTest {
    private final OwnerStatusCacheRepository cache = mock(OwnerStatusCacheRepository.class);
    private final OwnerStatusCacheInvalidationService service = new OwnerStatusCacheInvalidationService(cache);

    @AfterEach
    void cleanup() { TransactionSynchronizationManager.clear(); }

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

}
