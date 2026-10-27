package com.lunchcatch.owner.service;

import com.lunchcatch.owner.repository.OwnerStatusCacheRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerStatusCacheInvalidationService {
    private final OwnerStatusCacheRepository cache;

    /*
     * 점주 ACTIVE 전환 트랜잭션에서 호출한다.
     * 롤백 시에는 삭제하지 않고 커밋 성공 후 캐시를 삭제한다.
     * 삭제 실패나 동시 캐시 저장으로 이전 상태가 남을 수 있어 TTL과 DB 직접 조회를 함께 사용한다.
     */
    public void invalidateAfterCommit(Long ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("상태 캐시 무효화는 상태 변경 트랜잭션 안에서 요청해야 합니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    cache.delete(ownerId);
                } catch (DataAccessException e) {
                    log.warn("event=OWNER_STATUS_CACHE_INVALIDATION_FAILED ownerId={} exceptionType={} fallback=TTL_EXPIRY",
                            ownerId, e.getClass().getSimpleName(), e);
                }
            }
        });
    }
}
