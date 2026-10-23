package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.service.CampaignCancelService;
import com.launchcatch.campaign.service.CampaignStatusService;
import com.launchcatch.global.config.ClockConfig;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * POST /v1/owner/campaigns/{campaignId}:cancel 을 실제 입구부터 DB 재조회까지 확인한다(#597).
 *
 * 커밋 뒤의 상태와 두 트랜잭션의 경합을 봐야 해서 클래스 단위 @Transactional 을 NOT_SUPPORTED 로 덮는다.
 * CampaignPauseResumeSecurityIntegrationTest 와 같은 이유로 실제 점주와 가게 행을 만든다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignCancelIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String VERSION_CLAIM = "issuanceVersion";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CampaignCancelService cancelService;
    @Autowired private CampaignStatusService statusService;

    private final List<Long> createdStoreIds = new ArrayList<>();
    private final List<Long> createdOwnerIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdStoreIds.forEach(storeId -> jdbc.update(
                "delete from campaign_status_log where campaign_id in (select campaign_id from campaign where store_id = ?)",
                storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from campaign where store_id = ?", storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from store where store_id = ?", storeId));
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
    }

    @Test
    void SCHEDULED_캠페인을_취소하면_200과_DRAFT를_응답하고_OWNER_이력을_남긴다() throws Exception {
        Long ownerId = createActiveOwner();
        Long campaignId = campaignRepository.saveAndFlush(scheduled(ownerId, createStore(ownerId))).getId();

        mvc.perform(post(cancelPath(campaignId)).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaignId").value(campaignId.intValue()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        assertThat(campaignRepository.findById(campaignId).orElseThrow().getStatus()).isEqualTo(CampaignStatus.DRAFT);
        Map<String, Object> log = jdbc.queryForMap(
                "select from_status, to_status, actor_type from campaign_status_log where campaign_id = ?", campaignId);
        assertThat(log).containsEntry("from_status", "SCHEDULED")
                .containsEntry("to_status", "DRAFT")
                .containsEntry("actor_type", "OWNER");
    }

    // 취소 뒤에는 DRAFT 규칙이라 하루 예산과 집행 기간을 다시 고칠 수 있다(SCHEDULED 에서는 집행 기간을 못 바꾼다)
    @Test
    void 취소한_뒤에는_DRAFT_규칙대로_하루_예산과_집행_기간을_고칠_수_있다() throws Exception {
        Long ownerId = createActiveOwner();
        Long campaignId = campaignRepository.saveAndFlush(scheduled(ownerId, createStore(ownerId))).getId();
        LocalDate startDate = LocalDate.now(ClockConfig.ZONE).plusDays(2);

        mvc.perform(post(cancelPath(campaignId)).cookie(ownerToken(ownerId))).andExpect(status().isOk());
        mvc.perform(patch("/v1/owner/campaigns/" + campaignId).cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyBudget\":20000,\"startDate\":\"" + startDate
                                + "\",\"endDate\":\"" + startDate.plusDays(5) + "\"}"))
                .andExpect(status().isOk());

        Campaign reloaded = campaignRepository.findById(campaignId).orElseThrow();
        assertThat(reloaded.getDailyBudget()).isEqualTo(20_000L);
        assertThat(reloaded.getStartDate()).isEqualTo(startDate);
    }

    @Test
    void 이미_DRAFT면_다시_취소해도_200이다() throws Exception {
        Long ownerId = createActiveOwner();
        Long campaignId = campaignRepository.saveAndFlush(draft(ownerId, createStore(ownerId))).getId();

        mvc.perform(post(cancelPath(campaignId)).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void 집행이_시작된_캠페인은_409로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign active = scheduled(ownerId, createStore(ownerId));
        active.activate(LocalDateTime.now());
        Long campaignId = campaignRepository.saveAndFlush(active).getId();

        mvc.perform(post(cancelPath(campaignId)).cookie(ownerToken(ownerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-006"));
        assertThat(campaignRepository.findById(campaignId).orElseThrow().getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    }

    @Test
    void 본인_캠페인이_아니면_403으로_거부하고_상태를_바꾸지_않는다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        Long campaignId = campaignRepository.saveAndFlush(scheduled(otherOwnerId, createStore(otherOwnerId))).getId();

        mvc.perform(post(cancelPath(campaignId)).cookie(ownerToken(ownerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
        assertThat(campaignRepository.findById(campaignId).orElseThrow().getStatus())
                .isEqualTo(CampaignStatus.SCHEDULED);
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(post(cancelPath(1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    /*
     * 00:00 의 SCHEDULED -> ACTIVE 와 점주 취소가 같은 캠페인에 동시에 오면 행 잠금으로 줄을 서서
     * 한쪽만 이긴다. 둘 다 이기면 ACTIVE 로 집행되는데 점주는 취소됐다고 보거나 그 반대가 된다.
     */
    @Test
    void 취소와_00시_전환이_동시에_오면_한쪽만_이기고_상태가_그_결과와_맞는다() throws Exception {
        Long ownerId = createActiveOwner();
        Long campaignId = campaignRepository.saveAndFlush(scheduled(ownerId, createStore(ownerId))).getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        boolean cancelled;
        boolean activated;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> cancel = executor.submit(race(ready, go, () -> cancelService.cancel(ownerId, campaignId)));
            Future<Boolean> activate = executor.submit(race(ready, go, () -> {
                statusService.activate(campaignId);
                return null;
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            cancelled = cancel.get(10, TimeUnit.SECONDS);
            activated = activate.get(10, TimeUnit.SECONDS);
        }

        assertThat(cancelled).isNotEqualTo(activated);
        CampaignStatus status = campaignRepository.findById(campaignId).orElseThrow().getStatus();
        assertThat(status).isEqualTo(cancelled ? CampaignStatus.DRAFT : CampaignStatus.ACTIVE);
    }

    // 이기면 true, 상태가 맞지 않아 거부되면 false 다. 다른 예외는 그대로 던진다
    private Callable<Boolean> race(CountDownLatch ready, CountDownLatch go, Callable<?> action) {
        return () -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            try {
                action.call();
                return true;
            } catch (CampaignException | IllegalStateException e) {
                return false;
            }
        };
    }

    private String cancelPath(long campaignId) {
        return "/v1/owner/campaigns/" + campaignId + ":cancel";
    }

    private Cookie ownerToken(Long ownerId) {
        return new Cookie("ownerAccessToken", jwt.createAccessToken(ownerId, Role.OWNER, Map.of(VERSION_CLAIM, "0")));
    }

    private Long createActiveOwner() {
        String email = "campaign-cancel-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ACTIVE', ?, ?)", email, now, now);
        Long ownerId = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
        createdOwnerIds.add(ownerId);
        return ownerId;
    }

    private Long createStore(Long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into store (owner_id, name, category_code, representative_name, created_at, updated_at) "
                        + "values (?, ?, 'KOREAN', ?, ?, ?)",
                ownerId, "campaign-cancel-test-store-" + System.nanoTime(), "대표자", now, now);
        Long storeId = jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
        createdStoreIds.add(storeId);
        return storeId;
    }

    private Campaign draft(Long ownerId, Long storeId) {
        LocalDate startDate = LocalDate.now(ClockConfig.ZONE).plusDays(1);
        return Campaign.create(
                ownerId, storeId,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, startDate, startDate.plusDays(3),
                LocalDateTime.now());
    }

    private Campaign scheduled(Long ownerId, Long storeId) {
        Campaign campaign = draft(ownerId, storeId);
        campaign.schedule(LocalDateTime.now());
        return campaign;
    }
}
