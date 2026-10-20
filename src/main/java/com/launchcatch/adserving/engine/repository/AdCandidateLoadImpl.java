package com.launchcatch.adserving.engine.repository;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 같은 영업일로 다시 돌아도 행이 늘지 않게 upsert 한다. 소진 시각은 넣지도 바꾸지도 않는다.
 * 상태는 가진 전이 시각보다 늦을 때만 바꾼다. 이 판정이 state_changed_at 을 읽으므로 campaign_state 를 먼저 쓴다. 대입은 왼쪽부터 차례로 적용된다.
 */
@RequiredArgsConstructor
class AdCandidateLoadImpl implements AdCandidateLoad {

    private static final String UPSERT = """
            INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, store_image_url, category_code,
                latitude, longitude, target_radius, target_gender, target_age_groups, discount_target_type, discount_type,
                discount_value, discount_menu_name, usable_start_time, usable_end_time, issue_quantity, campaign_created_at,
                new_priority_until, campaign_state, state_changed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
            ON DUPLICATE KEY UPDATE
                store_id = n.store_id, store_name = n.store_name, store_image_url = n.store_image_url,
                category_code = n.category_code, latitude = n.latitude, longitude = n.longitude,
                target_radius = n.target_radius, target_gender = n.target_gender, target_age_groups = n.target_age_groups,
                discount_target_type = n.discount_target_type, discount_type = n.discount_type,
                discount_value = n.discount_value, discount_menu_name = n.discount_menu_name,
                usable_start_time = n.usable_start_time, usable_end_time = n.usable_end_time,
                issue_quantity = n.issue_quantity, campaign_created_at = n.campaign_created_at,
                new_priority_until = n.new_priority_until,
                campaign_state = IF(ad_candidate.state_changed_at IS NULL OR ad_candidate.state_changed_at < n.state_changed_at,
                    n.campaign_state, ad_candidate.campaign_state),
                state_changed_at = IF(ad_candidate.state_changed_at IS NULL OR ad_candidate.state_changed_at < n.state_changed_at,
                    n.state_changed_at, ad_candidate.state_changed_at),
                updated_at = n.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void upsertAll(List<CandidateRow> rows, LocalDateTime now) {
        if (rows.isEmpty()) {
            return;
        }
        Timestamp at = Timestamp.valueOf(now);
        jdbcTemplate.batchUpdate(UPSERT, rows, 500, (ps, r) -> {
            ps.setObject(1, Date.valueOf(r.businessDate()));
            ps.setLong(2, r.campaignId());
            ps.setLong(3, r.storeId());
            ps.setString(4, r.storeName());
            ps.setString(5, r.storeImageUrl());
            ps.setString(6, r.categoryCode());
            ps.setBigDecimal(7, r.latitude());
            ps.setBigDecimal(8, r.longitude());
            ps.setInt(9, r.targetRadius());
            ps.setString(10, r.targetGender());
            ps.setString(11, r.targetAgeGroups());
            ps.setString(12, r.discountTargetType());
            ps.setString(13, r.discountType());
            ps.setInt(14, r.discountValue());
            ps.setString(15, r.discountMenuName());
            ps.setTime(16, Time.valueOf(r.usableStartTime()));
            ps.setTime(17, Time.valueOf(r.usableEndTime()));
            ps.setInt(18, r.issueQuantity());
            ps.setTimestamp(19, Timestamp.valueOf(r.campaignCreatedAt()));
            ps.setObject(20, r.newPriorityUntil() == null ? null : Date.valueOf(r.newPriorityUntil()));
            ps.setString(21, r.campaignState().name());
            ps.setTimestamp(22, r.stateChangedAt() == null ? null : Timestamp.valueOf(r.stateChangedAt()));
            ps.setTimestamp(23, at);
            ps.setTimestamp(24, at);
        });
    }
}
