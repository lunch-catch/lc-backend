package com.launchcatch.owner.service;

import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.owner.repository.OwnerStatusCacheRepository;
import java.util.Optional;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerStatusService implements OwnerStatusQuery {
    private final OwnerRepository owners;
    private final OwnerStatusCacheRepository cache;

    @Override
    public Optional<OwnerAccountStatus> findStatus(Long ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        // 트랜잭션 중의 미커밋 상태는 공유 캐시에 게시하지 않는다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return findCurrentStatus(ownerId);
        }
        try {
            Optional<String> stored = cache.find(ownerId);
            if (stored.isPresent()) {
                return Optional.of(OwnerAccountStatus.valueOf(stored.get()));
            }
        } catch (DataAccessException | IllegalArgumentException e) {
            log.warn("event=OWNER_STATUS_CACHE_READ_FAILED ownerId={} exceptionType={} fallback=DB", ownerId, e.getClass().getSimpleName());
        }
        // DB에 존재하는 점주만 캐시하며, 캐시 저장 실패가 DB 조회 결과를 바꾸지 않게 한다.
        Optional<OwnerAccountStatus> status = findCurrentStatus(ownerId);
        status.ifPresent(value -> {
            try {
                cache.save(ownerId, value.name());
            } catch (DataAccessException e) {
                log.warn("event=OWNER_STATUS_CACHE_WRITE_FAILED ownerId={} exceptionType={} fallback=KEEP_DB_RESULT", ownerId, e.getClass().getSimpleName());
            }
        });
        return status;
    }

    @Override
    public Optional<OwnerAccountStatus> findCurrentStatus(Long ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return owners.findStatusById(ownerId).map(status -> OwnerAccountStatus.valueOf(status.name()));
    }

}
