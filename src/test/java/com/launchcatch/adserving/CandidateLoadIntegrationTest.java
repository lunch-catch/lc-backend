package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.job.LoadDailyCandidateJob;
import com.launchcatch.adserving.engine.service.CandidateLoadService;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.store.contract.StoreAdCandidateInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreMenuQueryResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * 00:00 묶음의 후보 적재 단계(DailyJob.run)를 입구로 MySQL 의 ad_candidate 까지 본다(#582).
 * 후보는 피드와 과금의 출발점이라 돈이 걸린다. 캠페인과 가게는 contract 를 목으로 두고 upsert 와 지우기는 실제 쿼리로 본다.
 */
@DisplayName("그날 후보 적재 통합")
class CandidateLoadIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 9, 30, 23, 0);

    @Autowired LoadDailyCandidateJob job;
    @Autowired CandidateLoadService candidateLoadService;

    @BeforeEach
    void setUp() {
        doReturn(LocalDateTime.of(2026, 10, 1, 0, 0, 5).atZone(ClockConfig.ZONE).toInstant()).when(clock).instant();
        doReturn(ClockConfig.ZONE).when(clock).getZone();
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(
                new StoreAdCandidateInfo(55L, "점심식당", "KOREAN", null, new BigDecimal("37.4979000"), new BigDecimal("127.0276000")),
                new StoreAdCandidateInfo(56L, "저녁식당", "JAPANESE", "https://cdn.example.com/56.jpg",
                        new BigDecimal("37.4999000"), new BigDecimal("127.0276000"))));
        when(storeQueryService.findMenusByIdIn(anyList())).thenReturn(
                new StoreMenuQueryResult(List.of(new StoreMenuInfo(7001L, 56L, null, "돈코츠 라멘", 9000L)), List.of()));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ad_candidate");
    }

    private static CampaignInfo campaign(long id, long storeId, CampaignStatus status, PauseReason reason,
                                         DiscountTargetType target, Long menuId, LocalDateTime changedAt) {
        return new CampaignInfo(id, 1L, storeId, status, 30, LocalTime.of(12, 0), LocalTime.of(14, 0),
                TODAY.minusDays(3), TODAY.plusDays(3), target, DiscountType.PERCENT, 20, menuId, 1000,
                TargetGender.ALL, List.of(AgeGroup.AGE_20S, AgeGroup.AGE_30S), LocalDateTime.of(2026, 9, 1, 9, 0),
                null, changedAt, reason, 10_000L);
    }

    private Map<String, Object> row(long campaignId) {
        return jdbcTemplate.queryForMap("""
                SELECT store_name, store_image_url, category_code, discount_target_type, discount_menu_name,
                       target_age_groups, campaign_state, state_changed_at, sold_out_at
                FROM ad_candidate WHERE business_date = ? AND campaign_id = ?
                """, TODAY, campaignId);
    }

    private long rows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ad_candidate", Long.class);
    }

    @Test
    void 서빙_대상을_캠페인과_가게_값으로_적재하고_피드가_그_후보를_찾는다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT),
                campaign(102, 56, CampaignStatus.PAUSED, PauseReason.OWNER, DiscountTargetType.MENU, 7001L, CHANGED_AT)));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of());

        job.run(TODAY);

        assertThat(row(101)).containsEntry("store_name", "점심식당").containsEntry("category_code", "KOREAN")
                .containsEntry("target_age_groups", "AGE_20S,AGE_30S").containsEntry("campaign_state", "SERVABLE")
                .containsEntry("state_changed_at", CHANGED_AT);
        assertThat(row(102)).containsEntry("discount_target_type", "MENU").containsEntry("discount_menu_name", "돈코츠 라멘")
                .containsEntry("campaign_state", "PAUSED");
        assertThat(jdbcTemplate.queryForList("""
                SELECT campaign_id FROM ad_candidate
                WHERE business_date = ? AND campaign_state = 'SERVABLE' AND sold_out_at IS NULL
                """, Long.class, TODAY)).containsExactly(101L);
    }

    @Test
    void 적재_끝에_정산의_예약액과_단가를_후보에_두고_그날_계획이_없으면_0으로_둔다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT),
                campaign(102, 56, CampaignStatus.ACTIVE, null, DiscountTargetType.MENU, 7001L, CHANGED_AT)));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of());
        when(budgetQueryService.getProgress(anyCollection(), eq(TODAY)))
                .thenReturn(List.of(new BudgetProgress(101L, 24_000, 10, 0)));

        job.run(TODAY);

        assertThat(budget(101)).containsEntry("reserved_amount", 24_000L).containsEntry("unit_price", 10L);
        assertThat(budget(102)).containsEntry("reserved_amount", 0L).containsEntry("unit_price", 0L);
    }

    @Test
    void 정산을_읽지_못해도_적재는_끝나고_예산은_비어_있다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of());
        when(budgetQueryService.getProgress(anyCollection(), eq(TODAY))).thenThrow(new IllegalStateException("카운터 장애"));

        job.run(TODAY);

        assertThat(rows()).isEqualTo(1);
        assertThat(budget(101)).containsEntry("reserved_amount", null).containsEntry("unit_price", null);
    }

    private Map<String, Object> budget(long campaignId) {
        return jdbcTemplate.queryForMap("SELECT reserved_amount, unit_price FROM ad_candidate WHERE business_date = ? AND campaign_id = ?",
                TODAY, campaignId);
    }

    // 이어받기와 수동 재실행이 같은 영업일로 다시 돌린다(런치캐치_배치_운영.md 2장)
    @Test
    void 다시_돌아도_행이_늘지_않고_소진_시각과_더_늦은_상태_통보가_남는다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of());
        job.run(TODAY);
        LocalDateTime soldOutAt = LocalDateTime.of(2026, 10, 1, 11, 3);
        LocalDateTime pausedAt = LocalDateTime.of(2026, 10, 1, 11, 10);
        jdbcTemplate.update("UPDATE ad_candidate SET sold_out_at = ?, campaign_state = 'PAUSED', state_changed_at = ? WHERE campaign_id = 101",
                soldOutAt, pausedAt);

        job.run(TODAY);

        assertThat(rows()).isEqualTo(1);
        assertThat(row(101)).containsEntry("sold_out_at", soldOutAt).containsEntry("campaign_state", "PAUSED")
                .containsEntry("state_changed_at", pausedAt);
    }

    // 적재가 캠페인을 읽은 뒤 멈춘 캠페인이다. 그때 온 통보는 행이 없어 반영되지 않았다(#582)
    @Test
    void 적재_중에_상태가_바뀐_캠페인은_재조회로_반영한다() {
        LocalDateTime pausedAt = CHANGED_AT.plusMinutes(70);
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.PAUSED, PauseReason.OWNER, DiscountTargetType.ALL, null, pausedAt)));

        job.run(TODAY);

        assertThat(row(101)).containsEntry("campaign_state", "PAUSED").containsEntry("state_changed_at", pausedAt);
    }

    // 이어받기 뒤 이전 서버가 늦게 커밋하는 경우다. 먼저 읽은 적재는 나중에 읽은 적재의 값을 되돌리지 못한다(#582)
    /*
     * 앞 실행이 101 을 SERVABLE 로 넣고 재조회 전에 멈췄다. 그사이 101 이 끝나 다시 돈 적재의 대상에 없다.
     * 다시 돈 단계의 재조회가 그 행도 PAUSED 로 고치는지 본다(#782).
     */
    @Test
    void 다시_돈_적재는_앞_실행이_넣고_대상에서_빠진_캠페인도_재조회로_고친다() {
        LocalDateTime endedAt = CHANGED_AT.plusMinutes(70);
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        candidateLoadService.load(TODAY);
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(102, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        // 계약처럼 요청한 ID 의 캠페인만 돌려준다
        List<CampaignInfo> campaigns = List.of(
                campaign(101, 55, CampaignStatus.ENDED, null, DiscountTargetType.ALL, null, endedAt),
                campaign(102, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT));
        when(campaignQueryService.findAllById(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return campaigns.stream().filter(c -> ids.contains(c.campaignId())).toList();
        });

        job.run(TODAY);

        assertThat(row(101)).containsEntry("campaign_state", "PAUSED").containsEntry("state_changed_at", endedAt);
        assertThat(row(102)).containsEntry("campaign_state", "SERVABLE");
    }

    @Test
    void 먼저_읽은_적재가_늦게_커밋해도_나중에_읽은_스냅샷_값이_남는다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of());
        job.run(TODAY);
        doReturn(LocalDateTime.of(2026, 10, 1, 0, 0, 1).atZone(ClockConfig.ZONE).toInstant()).when(clock).instant();
        when(storeQueryService.findAdCandidatesByStoreIds(anyList())).thenReturn(List.of(
                new StoreAdCandidateInfo(55L, "예전 상호", "KOREAN", null, new BigDecimal("37.4979000"), new BigDecimal("127.0276000"))));

        job.run(TODAY);

        assertThat(row(101)).containsEntry("store_name", "점심식당");
    }

    @Test
    void 가게나_메뉴를_찾지_못한_캠페인은_빼고_나머지를_적재한다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT),
                campaign(102, 99, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT),
                campaign(103, 56, CampaignStatus.ACTIVE, null, DiscountTargetType.MENU, 7999L, CHANGED_AT)));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of());

        job.run(TODAY);

        assertThat(jdbcTemplate.queryForList("SELECT campaign_id FROM ad_candidate", Long.class)).containsExactly(101L);
    }

    @Test
    void 보관_기간_7일이_지난_영업일의_후보만_지운다() {
        when(campaignQueryService.findServableOn(TODAY)).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        when(campaignQueryService.findAllById(anyList())).thenReturn(List.of());
        when(campaignQueryService.findServableOn(TODAY.minusDays(7))).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        when(campaignQueryService.findServableOn(TODAY.minusDays(8))).thenReturn(List.of(
                campaign(101, 55, CampaignStatus.ACTIVE, null, DiscountTargetType.ALL, null, CHANGED_AT)));
        job.run(TODAY.minusDays(8));
        job.run(TODAY.minusDays(7));

        job.run(TODAY);

        assertThat(jdbcTemplate.queryForList("SELECT business_date FROM ad_candidate ORDER BY business_date", LocalDate.class))
                .containsExactly(TODAY.minusDays(7), TODAY);
    }
}
