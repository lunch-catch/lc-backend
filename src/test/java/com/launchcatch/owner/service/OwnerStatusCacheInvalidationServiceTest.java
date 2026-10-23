package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.Level;
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
        var failure = new DataAccessResourceFailureException("delete offline");
        doThrow(failure).when(cache).delete(7L);
        try (var logs = LogCapture.attach(OwnerStatusCacheInvalidationService.class)) {
            service.invalidateAfterCommit(7L);
            assertThat(logs.events()).isEmpty();
            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCommit())).doesNotThrowAnyException();
            var event = logs.only();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=OWNER_STATUS_CACHE_INVALIDATION_FAILED", "ownerId=7",
                    "exceptionType=DataAccessResourceFailureException", "fallback=TTL_EXPIRY");
            assertThat(((ch.qos.logback.classic.spi.ThrowableProxy) event.getThrowableProxy()).getThrowable()).isSameAs(failure);
            assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
        }
    }

    @Test
    void 트랜잭션_없는_무효화는_거부한다() {
        assertThatThrownBy(() -> service.invalidateAfterCommit(7L)).isInstanceOf(IllegalStateException.class);
    }

}
