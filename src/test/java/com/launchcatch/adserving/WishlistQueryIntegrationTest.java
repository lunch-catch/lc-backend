package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.adserving.contract.WishlistQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * 쿠폰이 발급 자격에 쓰는 WishlistQueryService 빈부터 MySQL 의 wishlist 까지 본다.
 * 찜 여부가 쿠폰 발급을 막는 자격이라 권한에 걸린다. 단건과 목록이 같은 행을 같은 기준으로 읽는지 실제 쿼리로 본다.
 */
@DisplayName("찜 조회 contract 통합")
class WishlistQueryIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long MEMBER_ID = 3001L;
    private static final long OTHER_MEMBER_ID = 3002L;

    @Autowired WishlistQueryService wishlistQueryService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM wishlist");
    }

    private void insertWish(long memberId, long campaignId) {
        jdbcTemplate.update("""
                INSERT INTO wishlist (member_id, campaign_id, store_id, serve_id, created_at, updated_at)
                VALUES (?, ?, 55, NULL, NOW(6), NOW(6))
                """, memberId, campaignId);
    }

    @Test
    void 단건_조회와_목록_조회가_같은_찜을_유효하다고_본다() {
        insertWish(MEMBER_ID, 101L);
        insertWish(MEMBER_ID, 102L);
        insertWish(OTHER_MEMBER_ID, 103L);

        assertThat(wishlistQueryService.findWishedCampaignIds(MEMBER_ID)).containsExactlyInAnyOrder(101L, 102L);
        assertThat(wishlistQueryService.isWished(MEMBER_ID, 101L)).isTrue();
        assertThat(wishlistQueryService.isWished(MEMBER_ID, 102L)).isTrue();
        assertThat(wishlistQueryService.isWished(MEMBER_ID, 103L)).isFalse();
    }

    @Test
    void 찜을_지우면_두_조회_모두에서_빠진다() {
        insertWish(MEMBER_ID, 101L);

        jdbcTemplate.update("DELETE FROM wishlist WHERE member_id = ? AND campaign_id = ?", MEMBER_ID, 101L);

        assertThat(wishlistQueryService.isWished(MEMBER_ID, 101L)).isFalse();
        assertThat(wishlistQueryService.findWishedCampaignIds(MEMBER_ID)).isEmpty();
    }

    @Test
    void 찜이_없으면_목록은_빈_리스트다() {
        assertThat(wishlistQueryService.findWishedCampaignIds(MEMBER_ID)).isNotNull().isEmpty();
    }
}
