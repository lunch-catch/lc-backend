package com.lunchcatch.store.repository;

import com.lunchcatch.store.contract.FinalizedStoreCounts;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StoreStatsQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FinalizedStoreCounts finalizedStoreCounts(LocalDateTime dayStart, LocalDateTime nextDayStart) {
        return jdbc.queryForObject("""
                select coalesce(sum(finalized_at >= :dayStart and finalized_at < :nextDayStart), 0) as new_count,
                       coalesce(sum(finalized_at < :nextDayStart), 0) as cumulative_count
                from store
                where finalized = true
                """, Map.of("dayStart", dayStart, "nextDayStart", nextDayStart),
                (rs, rowNum) -> new FinalizedStoreCounts(rs.getLong("new_count"), rs.getLong("cumulative_count")));
    }
}