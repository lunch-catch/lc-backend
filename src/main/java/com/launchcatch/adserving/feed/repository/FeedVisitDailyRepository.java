package com.launchcatch.adserving.feed.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FeedVisitDailyRepository {

    private final JdbcTemplate jdbcTemplate;

    // 그날 첫 요청 위치 하나만 남긴다. 이미 있으면 그대로 둔다
    public void saveIfAbsent(LocalDate businessDate, Long memberId, BigDecimal latRounded, BigDecimal lngRounded,
                             LocalDateTime now) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO feed_visit_daily (business_date, member_id, lat_rounded, lng_rounded, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, businessDate, memberId, latRounded, lngRounded, now, now);
    }
}
