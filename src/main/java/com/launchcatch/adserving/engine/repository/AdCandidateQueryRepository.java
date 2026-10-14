package com.launchcatch.adserving.engine.repository;

import com.launchcatch.adserving.engine.ServableCandidate;
import com.launchcatch.global.geo.BoundingBox;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 서빙 경로가 읽는 유일한 후보 출처다(96행). 다른 도메인 테이블을 읽지 않는다.
 * 사각 범위로 먼저 걸러 idx_candidate_geo 를 타게 하고, 원 안인지는 부르는 쪽이 거리로 다시 본다.
 */
@Repository
@RequiredArgsConstructor
public class AdCandidateQueryRepository {

    private static final String FIND_SERVABLE = """
            SELECT campaign_id, store_id, store_name, category_code, latitude, longitude, target_radius,
                   target_gender, target_age_groups, discount_target_type, discount_type, discount_value,
                   usable_start_time, usable_end_time, issue_quantity, campaign_created_at
              FROM ad_candidate
             WHERE business_date = ?
               AND latitude BETWEEN ? AND ?
               AND longitude BETWEEN ? AND ?
               AND campaign_state = 'SERVABLE'
               AND sold_out_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    public List<ServableCandidate> findServable(LocalDate businessDate, BoundingBox box) {
        return jdbcTemplate.query(FIND_SERVABLE, AdCandidateQueryRepository::toCandidate,
                businessDate, box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude());
    }

    private static ServableCandidate toCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new ServableCandidate(
                rs.getLong("campaign_id"),
                rs.getLong("store_id"),
                rs.getString("store_name"),
                rs.getString("category_code"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                rs.getInt("target_radius"),
                rs.getString("target_gender"),
                Set.of(rs.getString("target_age_groups").split(",")),
                rs.getString("discount_target_type"),
                rs.getString("discount_type"),
                rs.getInt("discount_value"),
                rs.getTime("usable_start_time").toLocalTime(),
                rs.getTime("usable_end_time").toLocalTime(),
                rs.getInt("issue_quantity"),
                rs.getTimestamp("campaign_created_at").toLocalDateTime());
    }
}
