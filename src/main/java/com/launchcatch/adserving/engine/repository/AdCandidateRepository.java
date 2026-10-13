package com.launchcatch.adserving.engine.repository;

import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.global.geo.BoundingBox;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 그날 후보 스냅샷(ad_candidate)을 읽는다. 서빙 경로는 이 테이블만 읽는다(96행).
 * 쓰기는 00:00 적재 작업의 몫이라 여기에 없다.
 *
 * 복합 키(business_date, campaign_id) 테이블이라 BaseTimeEntity 를 쓸 수 없고 이 경로는 읽기만 해서
 * JPA 엔티티 대신 행을 바로 record 로 옮긴다.
 */
@Repository
@RequiredArgsConstructor
public class AdCandidateRepository {

    // 위경도 범위 비교는 idx_candidate_geo(business_date, latitude, longitude)가 받는다
    private static final String FIND_SERVABLE_SQL = """
            SELECT campaign_id, store_id, store_name, category_code, latitude, longitude, target_radius,
                   target_gender, target_age_groups, discount_target_type, discount_type, discount_value,
                   usable_start_time, usable_end_time, issue_quantity, campaign_created_at, new_priority_until
            FROM ad_candidate
            WHERE business_date = ?
              AND latitude BETWEEN ? AND ?
              AND longitude BETWEEN ? AND ?
              AND campaign_state = 'SERVABLE'
              AND sold_out_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    // 그날 서빙 가능한 후보 가운데 상자 안에 있는 것을 돌려준다
    public List<FeedCandidate> findServable(LocalDate businessDate, BoundingBox box) {
        return jdbcTemplate.query(FIND_SERVABLE_SQL, AdCandidateRepository::toCandidate,
                businessDate, box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude());
    }

    private static FeedCandidate toCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new FeedCandidate(
                rs.getLong("campaign_id"),
                rs.getLong("store_id"),
                rs.getString("store_name"),
                rs.getString("category_code"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                rs.getInt("target_radius"),
                TargetGender.valueOf(rs.getString("target_gender")),
                Arrays.stream(rs.getString("target_age_groups").split(","))
                        .map(AgeGroup::valueOf)
                        .collect(Collectors.toSet()),
                DiscountTargetType.valueOf(rs.getString("discount_target_type")),
                DiscountType.valueOf(rs.getString("discount_type")),
                rs.getInt("discount_value"),
                rs.getObject("usable_start_time", LocalTime.class),
                rs.getObject("usable_end_time", LocalTime.class),
                rs.getInt("issue_quantity"),
                rs.getObject("campaign_created_at", LocalDateTime.class),
                rs.getObject("new_priority_until", LocalDate.class));
    }
}
