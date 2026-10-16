package com.launchcatch.adserving.wishlist.repository;

import com.launchcatch.adserving.wishlist.entity.Wishlist;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

// 이미 찜한 캠페인이면 그대로 둔다(uk_wishlist_member_campaign). JPA 의 save 는 같은 키에서 제약 위반을 던진다.
@RequiredArgsConstructor
class WishlistInsertImpl implements WishlistInsert {

    private static final String INSERT = """
            INSERT IGNORE INTO wishlist (member_id, campaign_id, store_id, serve_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    public boolean insertIgnore(Wishlist wishlist) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        return jdbcTemplate.update(INSERT, wishlist.getMemberId(), wishlist.getCampaignId(), wishlist.getStoreId(),
                wishlist.getServeId() == null ? null : bytes(wishlist.getServeId()), now, now) == 1;
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
