package com.launchcatch.adserving.wishlist.repository;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WishlistRepository {

    private final JdbcTemplate jdbcTemplate;

    // since 이후에 찜한 캠페인이다
    public Set<Long> findCampaignIdsWishedSince(Long memberId, LocalDateTime since) {
        return new HashSet<>(jdbcTemplate.queryForList("""
                SELECT campaign_id FROM wishlist
                WHERE member_id = ? AND created_at >= ?
                """, Long.class, memberId, since));
    }
}
