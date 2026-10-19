package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.adserving.wishlist.job.EndedWishlistCleanupJob;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.campaign.contract.CampaignEndedEvent;
import com.launchcatch.global.config.ClockConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 캠페인 종료 이벤트와 00:00 정리 단계를 입구로 MySQL 의 wishlist 까지 본다(#553).
 * 끝난 캠페인의 찜이 쿠폰의 발급 자격에서 빠져야 해서 권한에 걸린다. 종료 표시와 정리는 직접 쓴 쿼리라 여기서만 실행된다.
 */
@DisplayName("끝난 캠페인의 찜 통합")
class WishlistCampaignEndedIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long MEMBER_ID = 3001L;
    private static final long OTHER_MEMBER_ID = 3002L;
    private static final LocalDateTime ENDED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired WishlistQueryService wishlistQueryService;
    @Autowired EndedWishlistCleanupJob cleanupJob;

    @BeforeEach
    void setUp() {
        doReturn(LocalDateTime.of(2026, 10, 1, 9, 0, 1).atZone(ClockConfig.ZONE).toInstant()).when(clock).instant();
        doReturn(ClockConfig.ZONE).when(clock).getZone();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM wishlist");
    }

    private void wish(long memberId, long campaignId, LocalDateTime endedAt) {
        jdbcTemplate.update("""
                INSERT INTO wishlist (member_id, campaign_id, store_id, serve_id, campaign_ended_at, created_at, updated_at)
                VALUES (?, ?, 55, NULL, ?, '2026-09-30 11:00:00', '2026-09-30 11:00:00')
                """, memberId, campaignId, endedAt);
    }

    private List<LocalDateTime> endedAtOf(long campaignId) {
        return jdbcTemplate.queryForList("SELECT campaign_ended_at FROM wishlist WHERE campaign_id = ? ORDER BY member_id",
                LocalDateTime.class, campaignId);
    }

    // 캠페인 종료는 campaign 의 트랜잭션 안에서 발행된다
    private void publishInTransaction(CampaignEndedEvent event) {
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(event));
    }

    @Test
    void 캠페인이_끝나면_그_캠페인의_찜만_종료로_표시되고_쿠폰의_찜_조회에서_빠진다() throws Exception {
        wish(MEMBER_ID, 101, null);
        wish(OTHER_MEMBER_ID, 101, null);
        wish(MEMBER_ID, 102, null);

        publishInTransaction(new CampaignEndedEvent(101L, ENDED_AT));

        assertThat(endedAtOf(101)).containsExactly(ENDED_AT, ENDED_AT);
        assertThat(endedAtOf(102)).containsExactly((LocalDateTime) null);
        assertThat(wishlistQueryService.isWished(MEMBER_ID, 101L)).isFalse();
        assertThat(wishlistQueryService.findWishedCampaignIds(MEMBER_ID)).containsExactly(102L);
        mockMvc.perform(get("/v1/wishlist").with(user(new CustomUserDetails(MEMBER_ID, Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wishlist.length()").value(2))
                .andExpect(jsonPath("$.data.wishlist[?(@.campaignId == 101)].campaignEnded").value(true))
                .andExpect(jsonPath("$.data.wishlist[?(@.campaignId == 102)].campaignEnded").value(false));
    }

    @Test
    void 같은_종료_이벤트가_다시_와도_처음_종료_시각이_남는다() {
        wish(MEMBER_ID, 101, null);
        publishInTransaction(new CampaignEndedEvent(101L, ENDED_AT));

        publishInTransaction(new CampaignEndedEvent(101L, ENDED_AT.plusHours(1)));

        assertThat(endedAtOf(101)).containsExactly(ENDED_AT);
    }

    @Test
    void 캠페인_종료가_되돌아가면_종료_표시도_되돌아간다() {
        wish(MEMBER_ID, 101, null);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new CampaignEndedEvent(101L, ENDED_AT));
            throw new IllegalStateException("캠페인 종료 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(endedAtOf(101)).containsExactly((LocalDateTime) null);
    }

    // 끝난 날을 빼고 3일 동안 보인다. 10/5 단계는 10/1 까지 끝난 찜만 지운다(#548)
    @Test
    void 정리_단계는_끝난_날을_빼고_3일이_지난_찜만_지운다() {
        wish(MEMBER_ID, 101, LocalDateTime.of(2026, 10, 1, 23, 59, 59));
        wish(MEMBER_ID, 102, LocalDateTime.of(2026, 10, 2, 0, 0));
        wish(MEMBER_ID, 103, null);

        cleanupJob.run(LocalDate.of(2026, 10, 5));

        assertThat(jdbcTemplate.queryForList("SELECT campaign_id FROM wishlist ORDER BY campaign_id", Long.class))
                .containsExactly(102L, 103L);
    }
}
