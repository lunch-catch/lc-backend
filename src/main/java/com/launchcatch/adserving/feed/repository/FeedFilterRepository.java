package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.engine.FeedSort;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FeedFilterRepository {

    private final JdbcTemplate jdbcTemplate;

    // categories 가 비어 있으면 전체 업종이다
    public record FeedFilterRow(Set<String> categories, FeedSort sort) {
    }

    // 저장한 적이 없으면 전체 업종과 거리순이다
    public FeedFilterRow findByMemberId(Long memberId) {
        List<String> sorts = jdbcTemplate.queryForList(
                "SELECT sort_type FROM feed_filter WHERE member_id = ?", String.class, memberId);
        if (sorts.isEmpty()) {
            return new FeedFilterRow(Set.of(), FeedSort.DISTANCE);
        }
        Set<String> categories = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT category_code FROM feed_filter_category WHERE member_id = ?", String.class, memberId));
        return new FeedFilterRow(categories, FeedSort.valueOf(sorts.getFirst()));
    }
}
