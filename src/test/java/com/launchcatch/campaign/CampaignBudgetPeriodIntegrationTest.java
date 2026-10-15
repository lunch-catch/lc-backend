package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private final List<Long> createdStoreIds = new ArrayList<>();
    private final List<Long> createdOwnerIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdStoreIds.forEach(storeId -> jdbc.update("delete from campaign where store_id = ?", storeId));
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

    private Campaign draftWithBudgetPeriod(Long ownerId, Long dailyBudget, LocalDate startDate, LocalDate endDate) {
        long storeId = System.nanoTime();
        createdStoreIds.add(storeId);
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
