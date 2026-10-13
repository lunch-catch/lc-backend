package com.launchcatch.adserving.engine.repository;

import com.launchcatch.adserving.engine.Candidate;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.global.geo.BoundingBox;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 그날 후보 스냅샷을 읽는다. 서빙 경로가 읽는 유일한 후보 출처다(96행).
 * 위경도 범위 비교로 idx_candidate_geo 를 타고, 정확한 거리는 부르는 쪽이 계산한다.
 */
@Repository
@RequiredArgsConstructor
public class AdCandidateRepository {

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

    // 영업일의 서빙 가능한 후보 중 상자 안에 있는 것을 돌려준다
    public List<Candidate> findServable(LocalDate businessDate, BoundingBox box) {
        return jdbcTemplate.query(FIND_SERVABLE_SQL, AdCandidateRepository::toCandidate,
                Date.valueOf(businessDate),
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude());
    }

    private static Candidate toCandidate(ResultSet rs, int rowNum) throws SQLException {
        Date newPriorityUntil = rs.getDate("new_priority_until");
        return new Candidate(
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
                rs.getTime("usable_start_time").toLocalTime(),
                rs.getTime("usable_end_time").toLocalTime(),
                rs.getInt("issue_quantity"),
                rs.getTimestamp("campaign_created_at").toLocalDateTime(),
                newPriorityUntil == null ? null : newPriorityUntil.toLocalDate());
    }
}
