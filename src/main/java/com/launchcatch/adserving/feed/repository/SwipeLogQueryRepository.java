package com.launchcatch.adserving.feed.repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 피드 배정이 읽는 스와이프 이력이다. 둘 다 idx_swipe_member_date 를 탄다.
@Repository
@RequiredArgsConstructor
public class SwipeLogQueryRepository {

    private static final String FIND_SWIPED = """
            SELECT DISTINCT campaign_id
              FROM swipe_log
             WHERE member_id = ?
               AND business_date = ?
            """;

    private static final String COUNT_WISHES_BY_CATEGORY = """
            SELECT category_code, COUNT(*) AS wish_count
              FROM swipe_log
             WHERE member_id = ?
               AND business_date BETWEEN ? AND ?
               AND action = 'WISH'
             GROUP BY category_code
            """;

    private final JdbcTemplate jdbcTemplate;

    // 그날 찜하거나 패스한 캠페인이다.
    public Set<Long> findSwipedCampaignIds(Long memberId, LocalDate businessDate) {
        List<Long> ids = jdbcTemplate.queryForList(FIND_SWIPED, Long.class, memberId, businessDate);
        return new HashSet<>(ids);
    }

    // 기간 안의 찜 수를 업종마다 센다. 양 끝 날짜를 포함한다.
    public Map<String, Long> countWishesByCategory(Long memberId, LocalDate from, LocalDate to) {
        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query(COUNT_WISHES_BY_CATEGORY,
                rs -> {
                    counts.put(rs.getString("category_code"), rs.getLong("wish_count"));
                },
                memberId, from, to);
        return counts;
    }
}
