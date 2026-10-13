package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.engine.FeedSort;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 피드 필터(feed_filter, feed_filter_category)를 읽는다.
 * 행이 없으면 기본값(전체 업종, 거리순)이라 정렬은 Optional 로, 업종은 빈 목록으로 돌려준다.
 */
@Repository
@RequiredArgsConstructor
public class FeedFilterRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<FeedSort> findSort(Long memberId) {
        return jdbcTemplate.queryForList("SELECT sort_type FROM feed_filter WHERE member_id = ?", String.class, memberId)
                .stream()
                .findFirst()
                .map(FeedSort::valueOf);
    }

    public List<String> findCategories(Long memberId) {
        return jdbcTemplate.queryForList(
                "SELECT category_code FROM feed_filter_category WHERE member_id = ?", String.class, memberId);
    }
}
