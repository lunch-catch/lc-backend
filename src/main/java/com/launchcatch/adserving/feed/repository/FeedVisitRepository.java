package com.launchcatch.adserving.feed.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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

    /*
     * 영업일 범위(양 끝 포함)의 접속 위치를 회원과 함께 한 행씩 넘긴다. 같은 회원의 같은 위치는 한 번만 넘긴다.
     * PK(business_date, member_id)의 앞 열로 범위만 읽는다. 대상 인원 집계(FR-042)가 부른다(#592).
     * 결과를 한꺼번에 받지 않고 스트리밍으로 받는다. 7일치를 목록으로 들면 회원 수에 비례해 힙을 크게 쓴다(#774).
     */
    public void forEachPointBetween(LocalDate from, LocalDate to, Consumer<VisitPoint> each) {
        jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT member_id, lat_rounded, lng_rounded FROM feed_visit_daily
                     WHERE business_date BETWEEN ? AND ?
                    """, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            // MySQL 드라이버는 이 값이어야 결과를 한 행씩 받는다
            statement.setFetchSize(Integer.MIN_VALUE);
            statement.setObject(1, from);
            statement.setObject(2, to);
            return statement;
        }, (RowCallbackHandler) rs -> each.accept(new VisitPoint(rs.getLong(1), rs.getDouble(2), rs.getDouble(3))));
    }

    /*
     * 보관 기간이 지난 영업일의 행을 limit 개까지 지운다.
     * 문장마다 따로 커밋한다(#610).
     */
    public int deleteBusinessDateBefore(LocalDate before, int limit) {
        return jdbcTemplate.update("DELETE FROM feed_visit_daily WHERE business_date < ? LIMIT ?", before, limit);
    }

    static BigDecimal round(double coordinate) {
        return BigDecimal.valueOf(coordinate).setScale(3, RoundingMode.HALF_UP);
    }

    public record VisitPoint(long memberId, double latitude, double longitude) {
    }

    // requestedAt 은 그 피드 요청을 받은 한국 시각이다. created_at 에 남는다.
    public record Visit(LocalDate businessDate, Long memberId, double latitude, double longitude, LocalDateTime requestedAt) {
    }
}
