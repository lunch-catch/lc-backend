package com.lunchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.adserving.contract.WishlistQueryService;
import com.lunchcatch.adserving.wishlist.job.EndedWishlistCleanupJob;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.CampaignEndedEvent;
import com.lunchcatch.campaign.contract.CampaignInfo;
import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.contract.DiscountTargetType;
import com.lunchcatch.campaign.contract.DiscountType;
import com.lunchcatch.campaign.contract.TargetGender;
import com.lunchcatch.global.config.ClockConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
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
        jdbcTemplate.update("DELETE FROM swipe_log");
        jdbcTemplate.update("DELETE FROM ad_candidate");
        // 찜 요청이 넣은 그날 당일 이력이다. 남기면 같은 회원과 날을 쓰는 다른 통합 테스트가 깨진다
        redisTemplate.delete("adserving:exclusion:2026-10-01:" + MEMBER_ID);
    }

    // 끝난 캠페인도 그날 후보 행은 남는다(PAUSED). 찜은 그 행이 있는지만 본다(#379)
    private void pausedCandidate(long campaignId) {
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, category_code,
                    latitude, longitude, target_radius, target_gender, target_age_groups,
                    discount_target_type, discount_type, discount_value, usable_start_time, usable_end_time,
                    issue_quantity, campaign_created_at, campaign_state, state_changed_at, created_at, updated_at)
                VALUES ('2026-10-01', ?, 55, '점심식당', 'KOREAN', 37.4979, 127.0276, 1000, 'ALL', 'AGE_20S',
                    'ALL', 'PERCENT', 20, '12:00', '14:00', 30, NOW(6), 'PAUSED', ?, NOW(6), NOW(6))
                """, campaignId, ENDED_AT);
    }

    private static CampaignInfo endedCampaign(long campaignId) {
        return new CampaignInfo(campaignId, 1L, 55L, CampaignStatus.ENDED, 30, LocalTime.of(12, 0), LocalTime.of(14, 0),
                LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 1), DiscountTargetType.ALL, DiscountType.PERCENT, 20, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S), LocalDateTime.of(2026, 9, 1, 9, 0), null, ENDED_AT, null, 10_000L);
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

    /*
     * 종료 이벤트가 지나간 뒤에 들어온 찜이다. 종료 이벤트는 다시 오지 않아 넣을 때 표시해야 한다.
     * 표시하지 않으면 쿠폰의 찜 조회에 유효한 찜으로 잡히고 정리 작업이 지우지 않는다(#780).
     */
    @Test
    void 끝난_캠페인을_가게_상세에서_찜하면_종료로_표시되고_쿠폰의_찜_조회에서_빠진다() throws Exception {
        pausedCandidate(101);
        publishInTransaction(new CampaignEndedEvent(101L, ENDED_AT));
        when(campaignQueryService.findAllById(List.of(101L))).thenReturn(List.of(endedCampaign(101)));

        mockMvc.perform(post("/v1/wishlist").contentType(MediaType.APPLICATION_JSON).content("{\"campaignId\": 101}")
                        .with(user(new CustomUserDetails(MEMBER_ID, Role.MEMBER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.campaignEnded").value(true));

        assertThat(endedAtOf(101)).containsExactly(ENDED_AT);
        assertThat(wishlistQueryService.isWished(MEMBER_ID, 101L)).isFalse();
    }

    @Test
    void 끝난_캠페인을_피드에서_찜해도_종료로_표시된다() throws Exception {
        pausedCandidate(101);
        publishInTransaction(new CampaignEndedEvent(101L, ENDED_AT));
        when(campaignQueryService.findAllById(List.of(101L))).thenReturn(List.of(endedCampaign(101)));

        mockMvc.perform(post("/v1/swipes").contentType(MediaType.APPLICATION_JSON).content("""
                        {"serveId": "%s", "campaignId": 101, "action": "WISH", "occurredAt": "2026-10-01T09:00:01.000+09:00"}
                        """.formatted(UUID.randomUUID()))
                        .with(user(new CustomUserDetails(MEMBER_ID, Role.MEMBER))))
                .andExpect(status().isOk());

        assertThat(endedAtOf(101)).containsExactly(ENDED_AT);
        assertThat(wishlistQueryService.findWishedCampaignIds(MEMBER_ID)).isEmpty();
    }
}
