package com.lunchcatch.owner.service;

import com.lunchcatch.global.logging.FailureLogDetails;
import com.lunchcatch.owner.contract.OwnerAccountStatus;
import com.lunchcatch.owner.contract.OwnerStatusQuery;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.owner.repository.OwnerStatusCacheRepository;
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
        } catch (DataAccessException e) {
            log.warn("event=OWNER_STATUS_CACHE_READ_FAILED ownerId={} exceptionType={} fallback=DB",
                    ownerId, e.getClass().getSimpleName(), e);
        } catch (IllegalArgumentException e) {
            // 잘못된 캐시값은 예외 메시지에 포함되므로 원문 대신 발생 위치만 남긴다.
            var details = FailureLogDetails.from(e);
            log.warn("event=OWNER_STATUS_CACHE_READ_FAILED ownerId={} exceptionType={} origin={} fallback=DB",
                    ownerId, e.getClass().getSimpleName(), details.origin());
        }
        // DB에 존재하는 점주만 캐시하며, 캐시 저장 실패가 DB 조회 결과를 바꾸지 않게 한다.
        Optional<OwnerAccountStatus> status = findCurrentStatus(ownerId);
        status.ifPresent(value -> {
            try {
                cache.save(ownerId, value.name());
            } catch (DataAccessException e) {
                log.warn("event=OWNER_STATUS_CACHE_WRITE_FAILED ownerId={} exceptionType={} fallback=KEEP_DB_RESULT",
                        ownerId, e.getClass().getSimpleName(), e);
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
