package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.Level;
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
        try (var logs = LogCapture.attach(OwnerStatusService.class)) {
            assertThat(service.findStatus(7L)).contains(OwnerAccountStatus.ACTIVE);
            assertThat(logs.events()).isEmpty();
        }
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
        var readFailure = new DataAccessResourceFailureException("read offline", new IllegalStateException("connection closed"));
        var writeFailure = new DataAccessResourceFailureException("write offline");
        when(cache.find(7L)).thenThrow(readFailure);
        when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.ACTIVE));
        doThrow(writeFailure).when(cache).save(7L, "ACTIVE");
        try (var logs = LogCapture.attach(OwnerStatusService.class)) {
            assertThat(service.findStatus(7L)).contains(OwnerAccountStatus.ACTIVE);
            assertThat(logs.events()).hasSize(2);
            var read = logs.events().get(0);
            assertThat(read.getLevel()).isEqualTo(Level.WARN);
            assertThat(read.getFormattedMessage()).contains("event=OWNER_STATUS_CACHE_READ_FAILED", "ownerId=7",
                    "exceptionType=DataAccessResourceFailureException", "fallback=DB");
            assertThat(((ch.qos.logback.classic.spi.ThrowableProxy) read.getThrowableProxy()).getThrowable()).isSameAs(readFailure);
            assertThat(read.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
            assertThat(read.getThrowableProxy().getCause().getClassName()).isEqualTo(IllegalStateException.class.getName());
            var write = logs.events().get(1);
            assertThat(write.getLevel()).isEqualTo(Level.WARN);
            assertThat(write.getFormattedMessage()).contains("event=OWNER_STATUS_CACHE_WRITE_FAILED", "ownerId=7",
                    "exceptionType=DataAccessResourceFailureException", "fallback=KEEP_DB_RESULT");
            assertThat(((ch.qos.logback.classic.spi.ThrowableProxy) write.getThrowableProxy()).getThrowable()).isSameAs(writeFailure);
            assertThat(write.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
        }
    }

    @Test
    void 잘못된_캐시값은_DB_상태로_교체한다() {
        String sensitiveValue = "owner@example.com sensitive-refresh-token " + "a".repeat(64);
        when(cache.find(7L)).thenReturn(Optional.of(sensitiveValue));
        when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.WITHDRAWN));
        try (var logs = LogCapture.attach(OwnerStatusService.class)) {
            assertThat(service.findStatus(7L)).contains(OwnerAccountStatus.WITHDRAWN);
            var event = logs.only();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("event=OWNER_STATUS_CACHE_READ_FAILED", "ownerId=7",
                    "exceptionType=IllegalArgumentException", "fallback=DB", "origin=")
                    .doesNotContain("origin=null", "owner@example.com", "sensitive-refresh-token", "a".repeat(64), "No enum constant");
            assertThat(event.getThrowableProxy()).isNull();
        }
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
    void DB_장애를_정상_상태로_처리하지_않는다() {
        when(cache.find(7L)).thenReturn(Optional.empty());
        when(owners.findStatusById(7L)).thenThrow(new DataAccessResourceFailureException("offline"));
        try (var logs = LogCapture.attach(OwnerStatusService.class)) {
            assertThatThrownBy(() -> service.findStatus(7L)).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(logs.events()).isEmpty();
        }
    }
}
