package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.dto.CampaignUpdateRequest;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.service.CampaignStatusService;
import com.launchcatch.campaign.service.CampaignTargetAudienceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * PATCH /v1/owner/campaigns/{campaignId}(하루 예산과 집행 기간)의 돈에 걸리는 동작을 실제
 * 입구부터 DB 재조회까지 확인한다. DRAFT는 즉시 반영, ACTIVE는 pending_daily_budget에만
 * 쓰고 daily_budget은 그대로 두는지가 핵심이라(#330) 단위 테스트의 Mock 저장소로는 실제
 * UPDATE 문이 어느 컬럼을 건드리는지 보장하지 않는다.
 *
 * CampaignTargetAudienceSecurityIntegrationTest와 같은 이유로 실제 점주 행을 준비하고
 * 클래스 단위 @Transactional을 NOT_SUPPORTED로 덮어 점주 AT 검증이 다른 연결에서도 이 행을
 * 보게 한다(feedback_campaign_integration_test_transactional_pitfall).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignBudgetPeriodIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String VERSION_CLAIM = "issuanceVersion";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CampaignTargetAudienceService targetAudienceService;
    @Autowired private CampaignStatusService campaignStatusService;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;

    private final List<Long> createdStoreIds = new ArrayList<>();
    private final List<Long> createdOwnerIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // campaignStatusService.pause/end가 쓴 CampaignStatusLog는 campaign의 FK 자식이라 먼저 지운다.
        createdStoreIds.forEach(storeId -> jdbc.update(
                "delete from campaign_status_log where campaign_id in (select campaign_id from campaign where store_id = ?)",
                storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from campaign where store_id = ?", storeId));
        // pause가 StoreCampaignSummaryUpdater를 부르면서(#468) 쓴 요약도 store의 FK 자식이라 store보다 먼저 지운다.
        createdStoreIds.forEach(storeId -> jdbc.update("delete from store_campaign_summary where store_id = ?", storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from store where store_id = ?", storeId));
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
    }

    @Test
    void DRAFT에서_예산과_기간이_즉시_반영된다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));
        Long campaignId = campaign.getId();
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(3);
        String body = """
                {
                  "dailyBudget": 5000,
                  "startDate": "%s",
                  "endDate": "%s"
                }
                """.formatted(startDate, endDate);

        mvc.perform(patch(path(campaignId))
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyBudget").value(5000))
                .andExpect(jsonPath("$.data.startDate").value(startDate.toString()))
                .andExpect(jsonPath("$.data.endDate").value(endDate.toString()));

        Campaign reloaded = campaignRepository.findById(campaignId).orElseThrow();
        assertThat(reloaded.getDailyBudget()).isEqualTo(5000L);
        assertThat(reloaded.getStartDate()).isEqualTo(startDate);
        assertThat(reloaded.getEndDate()).isEqualTo(endDate);
        assertThat(reloaded.getPendingDailyBudget()).isNull();
    }

    @Test
    void ACTIVE에서_하루예산을_바꾸면_예약값에만_쓰고_현재값은_그대로_둔다() throws Exception {
        Long ownerId = createActiveOwner();
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(3);
        Campaign campaign = draftWithBudgetPeriod(ownerId, 10_000L, startDate, endDate);
        campaign.schedule(LocalDateTime.now());
        campaign.activate(LocalDateTime.now());
        campaign = campaignRepository.saveAndFlush(campaign);
        Long campaignId = campaign.getId();
        String body = """
                {
                  "dailyBudget": 7000
                }
                """;

        mvc.perform(patch(path(campaignId))
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyBudget").value(10_000));

        Campaign reloaded = campaignRepository.findById(campaignId).orElseThrow();
        assertThat(reloaded.getDailyBudget()).isEqualTo(10_000L);
        assertThat(reloaded.getPendingDailyBudget()).isEqualTo(7000L);
    }

    @Test
    void 하루예산이_최소_하루예산_미만이면_422이다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));
        String body = """
                {
                  "dailyBudget": 999,
                  "startDate": "%s",
                  "endDate": "%s"
                }
                """.formatted(LocalDate.now().plusDays(1), LocalDate.now().plusDays(4));

        mvc.perform(patch(path(campaign.getId()))
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-007"));
    }

    /*
     * ACTIVE 하루 예산 변경(CampaignTargetAudienceService)과 중단(CampaignStatusService.pause)이
     * 같은 캠페인에 거의 동시에 들어와도 findByIdForUpdate 비관 잠금이 둘을 직렬화해 서로의
     * 변경을 잃지 않는지 확인한다(#330 자체 리뷰로 찾은 정합성 질문의 실증). pause는
     * pendingDailyBudget을 지우지 않아야 하므로, 먼저 커밋된 쪽의 예약값이 나중에 커밋되는
     * pause로 지워지지 않는지까지 함께 본다.
     */
    @Test
    void ACTIVE_하루예산_변경과_pause가_동시에_오면_서로의_변경을_잃지_않는다() throws Exception {
        Long ownerId = createActiveOwner();
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(3);
        Campaign campaign = draftWithBudgetPeriod(ownerId, 10_000L, startDate, endDate);
        campaign.schedule(LocalDateTime.now());
        campaign.activate(LocalDateTime.now());
        Long campaignId = campaignRepository.saveAndFlush(campaign).getId();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstCaller = new AtomicBoolean(false);
        /*
         * campaignRepository는 Spring Data 프록시라 call.callRealMethod()로 위임할 수 없어
         * 같은 조회를 EntityManager로 직접 재현한다(CampaignTargetAudienceConcurrencyIntegrationTest와
         * 같은 이유). 잠금 모드는 리포지토리의 @Lock(PESSIMISTIC_WRITE)와 같다.
         */
        doAnswer(call -> {
            Optional<Campaign> found = entityManager.createQuery(
                            "select c from Campaign c where c.id = :id", Campaign.class)
                    .setParameter("id", campaignId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream().findFirst();
            if (firstCaller.compareAndSet(false, true)) {
                locked.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return found;
        }).when(campaignRepository).findByIdForUpdate(campaignId);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> budgetPatch = executor.submit(() -> targetAudienceService.handle(ownerId, campaignId,
                    new CampaignUpdateRequest(null, null, null, 7000L, null, null)));
            assertThat(locked.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> pause = executor.submit(() ->
                    campaignStatusService.pause(campaignId, PauseReason.OWNER, LocalDate.now()));
            /*
             * 첫 트랜잭션이 행 잠금을 쥔 채 아직 커밋 전이라, pause의 findByIdForUpdate
             * (SELECT ... FOR UPDATE)는 실제 DB에서 블록되어야 한다.
             */
            assertThat(isDoneWithin(pause, 500)).isFalse();

            release.countDown();
            budgetPatch.get(15, TimeUnit.SECONDS);
            pause.get(15, TimeUnit.SECONDS);
        }

        Campaign reloaded = transactionTemplate.execute(status ->
                campaignRepository.findById(campaignId).orElseThrow());
        assertThat(reloaded.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(reloaded.getDailyBudget()).isEqualTo(10_000L);
        assertThat(reloaded.getPendingDailyBudget()).isEqualTo(7000L);
    }

    private boolean isDoneWithin(Future<?> future, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (future.isDone()) {
                return true;
            }
            Thread.sleep(20);
        }
        return future.isDone();
    }

    private String path(long campaignId) {
        return "/v1/owner/campaigns/" + campaignId;
    }

    private Cookie ownerToken(Long ownerId) {
        return new Cookie("accessToken", jwt.createAccessToken(ownerId, Role.OWNER, Map.of(VERSION_CLAIM, "0")));
    }

    private Long createActiveOwner() {
        String email = "campaign-budget-period-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ACTIVE', ?, ?)", email, now, now);
        Long ownerId = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
        createdOwnerIds.add(ownerId);
        return ownerId;
    }

    private Campaign draft(Long ownerId) {
        return draftWithBudgetPeriod(ownerId, null, null, null);
    }

    // campaignStatusService.pause가 StoreCampaignSummaryUpdater를 부르면서(#468) storeId가 store 테이블의 하드 FK를 걸어, 더는 임의의 가짜 ID를 쓸 수 없다.
    private Long createStore(Long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into store (owner_id, name, category_code, representative_name, created_at, updated_at) "
                        + "values (?, ?, 'KOREAN', ?, ?, ?)",
                ownerId, "campaign-budget-period-test-store-" + System.nanoTime(), "대표자", now, now);
        Long storeId = jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
        createdStoreIds.add(storeId);
        return storeId;
    }

    private Campaign draftWithBudgetPeriod(Long ownerId, Long dailyBudget, LocalDate startDate, LocalDate endDate) {
        long storeId = createStore(ownerId);
        return Campaign.create(
                ownerId, storeId,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                dailyBudget, startDate, endDate,
                LocalDateTime.now());
    }
}
