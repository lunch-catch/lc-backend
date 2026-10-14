package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.engine.FeedFilter;
import com.launchcatch.adserving.engine.FeedSort;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 피드 필터를 읽는다. 저장한 적이 없으면 기본값(전체 업종, 거리순)이다.
@Repository
@RequiredArgsConstructor
public class FeedFilterQueryRepository {

    private static final String FIND_SORT = "SELECT sort_type FROM feed_filter WHERE member_id = ?";
    private static final String FIND_CATEGORIES = "SELECT category_code FROM feed_filter_category WHERE member_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public FeedFilter find(Long memberId) {
        List<String> sorts = jdbcTemplate.queryForList(FIND_SORT, String.class, memberId);
        if (sorts.isEmpty()) {
            return FeedFilter.DEFAULT;
        }
        Set<String> categories = new HashSet<>(jdbcTemplate.queryForList(FIND_CATEGORIES, String.class, memberId));
        return new FeedFilter(categories, FeedSort.valueOf(sorts.getFirst()));
    }
}
