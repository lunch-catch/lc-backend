package com.launchcatch.adserving.feed.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 그날 첫 피드 요청 위치(feed_visit_daily)를 쓴다.
 * (영업일, 사용자)당 한 행이라 INSERT IGNORE 로 두 번째부터는 버린다.
 * 위치는 저장 위치가 아니므로 소수점 3자리로 반올림해 남긴다(api-spec.md 의 개인정보 절).
 */
@Repository
@RequiredArgsConstructor
public class FeedVisitDailyRepository {

    private static final int COORDINATE_SCALE = 3;

    private static final String INSERT_IGNORE_SQL = """
            INSERT IGNORE INTO feed_visit_daily
                (business_date, member_id, lat_rounded, lng_rounded, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public void saveFirst(LocalDate businessDate, Long memberId, double latitude, double longitude, LocalDateTime now) {
        jdbcTemplate.update(INSERT_IGNORE_SQL, businessDate, memberId,
                round(latitude), round(longitude), now, now);
    }

    private static BigDecimal round(double coordinate) {
        return BigDecimal.valueOf(coordinate).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }
}
