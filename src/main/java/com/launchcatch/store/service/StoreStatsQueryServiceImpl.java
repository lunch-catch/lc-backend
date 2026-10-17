package com.launchcatch.store.service;

import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.store.contract.FinalizedStoreCounts;
import com.launchcatch.store.contract.StoreStatsQueryService;
import com.launchcatch.store.repository.StoreStatsQueryRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class StoreStatsQueryServiceImpl implements StoreStatsQueryService {
    private final StoreStatsQueryRepository repository;

    @Override
    public FinalizedStoreCounts finalizedStoreCounts(LocalDate businessDate) {
        Objects.requireNonNull(businessDate, "영업일은 필수다.");
        // DATETIME(6)에 저장한 KST 벽시계와 비교하므로 UTC 시각으로 변환하지 않는다.
        LocalDateTime dayStart = businessDate.atStartOfDay(ClockConfig.ZONE).toLocalDateTime();
        LocalDateTime nextDayStart = businessDate.plusDays(1).atStartOfDay(ClockConfig.ZONE).toLocalDateTime();
        return repository.finalizedStoreCounts(dayStart, nextDayStart);
    }
}