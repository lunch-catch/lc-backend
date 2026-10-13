package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.SwipeHistory;
import com.launchcatch.adserving.feed.entity.SwipeAction;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 스와이프 이력(swipe_log)을 읽는다.
 * 쓰기는 POST /v1/swipes 의 몫이다
 */
@Repository
@RequiredArgsConstructor
public class SwipeLogRepository {

    // idx_swipe_member_date(member_id, business_date)가 받는다
    private static final String FIND_SINCE_SQL = """
            SELECT business_date, campaign_id, category_code, action
            FROM swipe_log
            WHERE member_id = ? AND business_date >= ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public List<SwipeHistory> findByMemberSince(Long memberId, LocalDate fromDate) {
        return jdbcTemplate.query(FIND_SINCE_SQL, (rs, rowNum) -> new SwipeHistory(
                rs.getObject("business_date", LocalDate.class),
                rs.getLong("campaign_id"),
                rs.getString("category_code"),
                SwipeAction.valueOf(rs.getString("action"))), memberId, fromDate);
    }
}
