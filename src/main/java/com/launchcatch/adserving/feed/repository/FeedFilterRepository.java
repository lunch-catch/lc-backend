package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.engine.FeedFilter;
import com.launchcatch.adserving.engine.FeedSort;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FeedFilterRepository {

    private static final String FIND_SORT_SQL = "SELECT sort_type FROM feed_filter WHERE member_id = ?";
    private static final String FIND_CATEGORIES_SQL =
            "SELECT category_code FROM feed_filter_category WHERE member_id = ?";

    private final JdbcTemplate jdbcTemplate;

    // 행이 없으면 기본값(전체 업종, 거리순)을 돌려준다
    public FeedFilter find(Long memberId) {
        List<String> sorts = jdbcTemplate.queryForList(FIND_SORT_SQL, String.class, memberId);
        if (sorts.isEmpty()) {
            return FeedFilter.DEFAULT;
        }
        List<String> categories = jdbcTemplate.queryForList(FIND_CATEGORIES_SQL, String.class, memberId);
        return new FeedFilter(FeedSort.valueOf(sorts.getFirst()), new HashSet<>(categories));
    }
}
