package com.launchcatch.adserving.feed.repository;

import java.sql.Date;
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

    private static final String FIND_SWIPED_SQL = """
            SELECT DISTINCT campaign_id
              FROM swipe_log
             WHERE member_id = ? AND business_date = ?
            """;

    private static final String COUNT_WISHES_SQL = """
            SELECT category_code, COUNT(*) AS wish_count
              FROM swipe_log
             WHERE member_id = ? AND business_date BETWEEN ? AND ? AND action = 'WISH'
             GROUP BY category_code
            """;

    private final JdbcTemplate jdbcTemplate;

    // 회원이 그날 찜하거나 패스한 캠페인이다(102행 (9))
    public Set<Long> findCampaignIdsSwipedOn(Long memberId, LocalDate businessDate) {
        return new HashSet<>(jdbcTemplate.queryForList(FIND_SWIPED_SQL, Long.class, memberId, Date.valueOf(businessDate)));
    }

    /*
     * 기간 안에 회원이 업종마다 찜한 횟수다.
     * 관련성 점수의 카테고리 선호도에 쓴다.
     */
    public Map<String, Long> countWishesByCategory(Long memberId, LocalDate from, LocalDate to) {
        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query(COUNT_WISHES_SQL,
                rs -> {
                    counts.put(rs.getString("category_code"), rs.getLong("wish_count"));
                },
                memberId, Date.valueOf(from), Date.valueOf(to));
        return counts;
    }
}
