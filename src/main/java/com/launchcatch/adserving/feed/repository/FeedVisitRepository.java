package com.launchcatch.adserving.feed.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * (영업일, 사용자)마다 그날 첫 피드 요청 위치 1건을 남긴다. 추천 인원(FR-042)이 읽는다.
 * INSERT IGNORE 라 그날 두 번째부터는 남지 않는다. 좌표는 소수점 3자리(약 100m)로 반올림한다(api-spec.md 개인정보).
 */
@Repository
@RequiredArgsConstructor
public class FeedVisitRepository {

    private static final String INSERT = """
            INSERT IGNORE INTO feed_visit_daily (business_date, member_id, lat_rounded, lng_rounded, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public void insertAll(List<Visit> visits) {
        jdbcTemplate.batchUpdate(INSERT, visits, visits.size(), (ps, v) -> {
            Timestamp at = Timestamp.valueOf(v.requestedAt());
            ps.setObject(1, v.businessDate());
            ps.setLong(2, v.memberId());
            ps.setBigDecimal(3, round(v.latitude()));
            ps.setBigDecimal(4, round(v.longitude()));
            ps.setTimestamp(5, at);
            ps.setTimestamp(6, at);
        });
    }

    static BigDecimal round(double coordinate) {
        return BigDecimal.valueOf(coordinate).setScale(3, RoundingMode.HALF_UP);
    }

    // requestedAt 은 그 피드 요청을 받은 한국 시각이다. created_at 에 남는다.
    public record Visit(LocalDate businessDate, Long memberId, double latitude, double longitude, LocalDateTime requestedAt) {
    }
}
