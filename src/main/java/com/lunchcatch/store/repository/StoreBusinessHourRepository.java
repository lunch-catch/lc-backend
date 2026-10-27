package com.lunchcatch.store.repository;

import com.lunchcatch.store.contract.OwnerStoreSnapshot.BusinessHour;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StoreBusinessHourRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // 호출자가 점주 행을 잠근 쓰기 트랜잭션에서 검증된 일주일 전체를 교체한다.
    public void replace(Long storeId, List<BusinessHour> hours, LocalDateTime now) {
        jdbc.update("delete from store_business_hour where store_id = :storeId", Map.of("storeId", storeId));
        var rows = hours.stream().map(hour -> new MapSqlParameterSource()
                .addValue("storeId", storeId).addValue("day", hour.dayOfWeek())
                .addValue("open", hour.openTime()).addValue("close", hour.closeTime())
                .addValue("closed", hour.isClosed()).addValue("breakStart", hour.breakStartTime())
                .addValue("breakEnd", hour.breakEndTime()).addValue("lastOrder", hour.lastOrderTime())
                .addValue("now", now)).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into store_business_hour (store_id, day_of_week, open_time, close_time, closed,
                    break_start_time, break_end_time, last_order_time, created_at, updated_at)
                values (:storeId, :day, :open, :close, :closed, :breakStart, :breakEnd, :lastOrder, :now, :now)
                """, rows);
    }
}
