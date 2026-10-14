package com.launchcatch.adserving.feed.repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SwipeLogRepository {

    private final JdbcTemplate jdbcTemplate;

    // 그날 찜하거나 패스한 캠페인이다. idx_swipe_member_date 를 탄다
    public Set<Long> findCampaignIdsSwipedOn(Long memberId, LocalDate businessDate) {
        return new HashSet<>(jdbcTemplate.queryForList("""
                SELECT DISTINCT campaign_id FROM swipe_log
                WHERE member_id = ? AND business_date = ?
                """, Long.class, memberId, businessDate));
    }

    // from 부터 to 까지 업종별 찜 횟수다
    public Map<String, Long> countWishesByCategory(Long memberId, LocalDate from, LocalDate to) {
        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query("""
                SELECT category_code, COUNT(*) AS wish_count FROM swipe_log
                WHERE member_id = ? AND business_date BETWEEN ? AND ? AND action = 'WISH'
                GROUP BY category_code
                """, resultSet -> {
            counts.put(resultSet.getString("category_code"), resultSet.getLong("wish_count"));
        }, memberId, from, to);
        return counts;
    }
}
