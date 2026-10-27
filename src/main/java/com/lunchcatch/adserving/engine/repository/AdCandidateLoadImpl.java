package com.lunchcatch.adserving.engine.repository;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 같은 영업일로 다시 돌아도 행이 늘지 않게 upsert 한다. 소진 시각은 넣지도 바꾸지도 않는다.
 * 스냅샷 값은 이 적재가 원천을 읽은 시각(snapshot_read_at)이 가진 값보다 늦거나 같을 때만 바꾼다. 이어받기 뒤 늦게 커밋된 이전 적재가 새 값을 되돌리지 않게 한다(#582).
 * 상태는 가진 전이 시각보다 늦을 때만 바꾼다. 두 판정이 기존 값을 읽으므로 snapshot_read_at 과 state_changed_at 을 마지막에 쓴다. 대입은 왼쪽부터 차례로 적용된다.
 */
@RequiredArgsConstructor
class AdCandidateLoadImpl implements AdCandidateLoad {

    // 고정된 문장이다. 스냅샷 열(가게, 위치, 노출 대상, 할인, 사용 시간 등)은 읽은 시각으로, 서빙 상태는 전이 시각으로 지킨다
    private static final String UPSERT = """
            INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, store_image_url, category_code,
                latitude, longitude, target_radius, target_gender, target_age_groups, discount_target_type, discount_type,
                discount_value, discount_menu_name, usable_start_time, usable_end_time, issue_quantity, campaign_created_at,
                new_priority_until, campaign_state, state_changed_at, snapshot_read_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n
            ON DUPLICATE KEY UPDATE
                store_id = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.store_id, ad_candidate.store_id),
                store_name = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.store_name, ad_candidate.store_name),
                store_image_url = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.store_image_url, ad_candidate.store_image_url),
                category_code = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.category_code, ad_candidate.category_code),
                latitude = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.latitude, ad_candidate.latitude),
                longitude = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.longitude, ad_candidate.longitude),
                target_radius = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.target_radius, ad_candidate.target_radius),
                target_gender = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.target_gender, ad_candidate.target_gender),
                target_age_groups = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.target_age_groups, ad_candidate.target_age_groups),
                discount_target_type = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.discount_target_type, ad_candidate.discount_target_type),
                discount_type = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.discount_type, ad_candidate.discount_type),
                discount_value = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.discount_value, ad_candidate.discount_value),
                discount_menu_name = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.discount_menu_name, ad_candidate.discount_menu_name),
                usable_start_time = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.usable_start_time, ad_candidate.usable_start_time),
                usable_end_time = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.usable_end_time, ad_candidate.usable_end_time),
                issue_quantity = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.issue_quantity, ad_candidate.issue_quantity),
                campaign_created_at = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.campaign_created_at, ad_candidate.campaign_created_at),
                new_priority_until = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.new_priority_until, ad_candidate.new_priority_until),
                campaign_state = IF(ad_candidate.state_changed_at IS NULL OR ad_candidate.state_changed_at < n.state_changed_at,
                    n.campaign_state, ad_candidate.campaign_state),
                state_changed_at = IF(ad_candidate.state_changed_at IS NULL OR ad_candidate.state_changed_at < n.state_changed_at,
                    n.state_changed_at, ad_candidate.state_changed_at),
                snapshot_read_at = IF(ad_candidate.snapshot_read_at IS NULL OR ad_candidate.snapshot_read_at <= n.snapshot_read_at,
                    n.snapshot_read_at, ad_candidate.snapshot_read_at),
                updated_at = n.updated_at
            """;

    private static final String UPDATE_BUDGET = """
            UPDATE ad_candidate
               SET reserved_amount = ?, unit_price = ?, updated_at = ?
             WHERE business_date = ? AND campaign_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void upsertAll(List<CandidateRow> rows, LocalDateTime readAt, LocalDateTime now) {
        if (rows.isEmpty()) {
            return;
        }
        Timestamp read = Timestamp.valueOf(readAt);
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
            ps.setTimestamp(23, read);
            ps.setTimestamp(24, at);
            ps.setTimestamp(25, at);
        });
    }

    @Override
    public void updateBudgets(LocalDate businessDate, List<CandidateBudget> budgets, LocalDateTime now) {
        if (budgets.isEmpty()) {
            return;
        }
        Timestamp at = Timestamp.valueOf(now);
        Date date = Date.valueOf(businessDate);
        jdbcTemplate.batchUpdate(UPDATE_BUDGET, budgets, 500, (ps, b) -> {
            ps.setLong(1, b.reservedAmount());
            ps.setLong(2, b.unitPrice());
            ps.setTimestamp(3, at);
            ps.setDate(4, date);
            ps.setLong(5, b.campaignId());
        });
    }
}
