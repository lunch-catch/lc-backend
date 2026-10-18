package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.global.config.ClockConfig;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * POST /v1/owner/campaigns/{campaignId}:pause, :resume의 보안 체인과 가게 요약 동기화를 실제
 * 입구부터 확인한다. CampaignTargetAudienceSecurityIntegrationTest와 같은 이유로 실제 점주 행을
 * 준비하고 클래스 단위 @Transactional을 NOT_SUPPORTED로 덮는다
 * (feedback_campaign_integration_test_transactional_pitfall).
 *
 * StoreCampaignSummaryUpdater가 실제 구현(spy)으로 동작해 store_campaign_summary.store_id가
 * store 테이블의 하드 FK를 걸기 때문에, 이 테스트만 실제 store 행을 함께 만든다(#468).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignPauseResumeSecurityIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String VERSION_CLAIM = "issuanceVersion";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdStoreIds = new ArrayList<>();
    private final List<Long> createdOwnerIds = new ArrayList<>();
    private final List<Long> createdAdminIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdStoreIds.forEach(storeId -> jdbc.update(
                "delete from campaign_status_log where campaign_id in (select campaign_id from campaign where store_id = ?)",
                storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from campaign where store_id = ?", storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from store_campaign_summary where store_id = ?", storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from store_menu where store_id = ?", storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from store where store_id = ?", storeId));
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
    }

    @Test
    void ACTIVE_캠페인을_중단하면_200과_PAUSED를_응답하고_가게_요약에도_반영한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = createStore(ownerId);
        Campaign campaign = campaignRepository.saveAndFlush(activeCampaign(ownerId, storeId));
        Long campaignId = campaign.getId();

        mvc.perform(post(pausePath(campaignId)).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.campaignId").value(campaignId.intValue()))
                .andExpect(jsonPath("$.data.status").value("PAUSED"));

        Campaign reloaded = campaignRepository.findById(campaignId).orElseThrow();
        assertThat(reloaded.getStatus().name()).isEqualTo("PAUSED");
        assertThat(reloaded.getPausedReason()).isEqualTo(PauseReason.OWNER);

        String summaryStatus = jdbc.queryForObject(
                "select campaign_status from store_campaign_summary where campaign_id = ? and business_date = ?",
                String.class, campaignId, LocalDate.now(ClockConfig.ZONE).toString());
        assertThat(summaryStatus).isEqualTo("PAUSED");
    }

    @Test
    void 이미_점주_중단으로_PAUSED면_다시_중단해도_200이다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = createStore(ownerId);
        Campaign campaign = pausedByOwnerCampaign(ownerId, storeId);
        Long campaignId = campaignRepository.saveAndFlush(campaign).getId();

        mvc.perform(post(pausePath(campaignId)).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"));
    }

    @Test
    void DRAFT면_중단을_409로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = createStore(ownerId);
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId, storeId));

        mvc.perform(post(pausePath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-006"));
    }

    @Test
    void 본인_가게의_캠페인이_아니면_중단을_403으로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        Long otherStoreId = createStore(otherOwnerId);
        Campaign campaign = campaignRepository.saveAndFlush(activeCampaign(otherOwnerId, otherStoreId));

        mvc.perform(post(pausePath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void 캠페인이_없으면_중단을_403으로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();

        mvc.perform(post(pausePath(999_999_999L)).cookie(ownerToken(ownerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void 토큰이_없으면_중단을_401로_거부한다() throws Exception {
        mvc.perform(post(pausePath(1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_중단을_403으로_거부한다(Role role) throws Exception {
        mvc.perform(post(pausePath(1L)).cookie(nonOwnerToken(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void PAUSED_캠페인을_재개하면_200과_ACTIVE를_응답하고_가게_요약에도_반영한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = createStore(ownerId);
        Campaign campaign = pausedByOwnerCampaign(ownerId, storeId);
        Long campaignId = campaignRepository.saveAndFlush(campaign).getId();

        mvc.perform(post(resumePath(campaignId)).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaignId").value(campaignId.intValue()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        Campaign reloaded = campaignRepository.findById(campaignId).orElseThrow();
        assertThat(reloaded.getStatus().name()).isEqualTo("ACTIVE");

        String summaryStatus = jdbc.queryForObject(
                "select campaign_status from store_campaign_summary where campaign_id = ? and business_date = ?",
                String.class, campaignId, LocalDate.now(ClockConfig.ZONE).toString());
        assertThat(summaryStatus).isEqualTo("ACTIVE");
    }

    @Test
    void DRAFT면_재개를_409로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = createStore(ownerId);
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId, storeId));

        mvc.perform(post(resumePath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-006"));
    }

    /*
     * 캠페인 등록 1단계(CampaignCouponConditionService)가 targetMenuId의 가게 소속을 검증하지
     * 않아(#468 자체 리뷰로 찾음), MENU 할인 캠페인이 다른 가게의 메뉴를 참조한 채로 존재할 수
     * 있다. 그 불일치가 StoreCampaignSummaryUpdater.update()에서 처음 걸러지는데, 500으로
     * 답하고 캠페인 상태는 그대로(트랜잭션 롤백) 남아야 한다.
     */
    @Test
    void 다른_가게의_메뉴를_참조하면_중단을_500으로_거부하고_상태를_되돌리지_않는다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = createStore(ownerId);
        Long otherOwnerId = createActiveOwner();
        Long otherStoreId = createStore(otherOwnerId);
        Long otherStoresMenuId = createMenu(otherStoreId);
        Campaign campaign = campaignRepository.saveAndFlush(
                activeCampaignWithMenu(ownerId, storeId, otherStoresMenuId));
        Long campaignId = campaign.getId();

        mvc.perform(post(pausePath(campaignId)).cookie(ownerToken(ownerId)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON-001"));

        Campaign reloaded = campaignRepository.findById(campaignId).orElseThrow();
        assertThat(reloaded.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(reloaded.getPausedReason()).isNull();
    }

    private String pausePath(long campaignId) {
        return "/v1/owner/campaigns/" + campaignId + ":pause";
    }

    private String resumePath(long campaignId) {
        return "/v1/owner/campaigns/" + campaignId + ":resume";
    }

    private Cookie ownerToken(Long ownerId) {
        return new Cookie("accessToken", jwt.createAccessToken(ownerId, Role.OWNER, Map.of(VERSION_CLAIM, "0")));
    }

    // 사용자 역할은 계정 존재를 확인하지 않아 임의의 식별자로 충분하다(owner 도메인과 같은 이유).
    private Cookie nonOwnerToken(Role role) {
        if (role == Role.MEMBER) {
            return new Cookie("accessToken", jwt.createAccessToken(1L, role));
        }
        String loginId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, "test-hash", "campaign-pause-resume-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("accessToken", jwt.createAccessToken(adminId, role));
    }

    private Long createActiveOwner() {
        String email = "campaign-pause-resume-test-" + System.nanoTime() + "@example.com";
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
                ownerId, "campaign-pause-resume-test-store-" + System.nanoTime(), "대표자", now, now);
        Long storeId = jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
        createdStoreIds.add(storeId);
        return storeId;
    }

    private Long createMenu(Long storeId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into store_menu (store_id, name, price, sort_order, created_at, updated_at) "
                + "values (?, ?, 8000, 1, ?, ?)", storeId, "campaign-pause-resume-test-menu-" + System.nanoTime(),
                now, now);
        return jdbc.queryForObject("select store_menu_id from store_menu where store_id = ?", Long.class, storeId);
    }

    private Campaign draft(Long ownerId, Long storeId) {
        LocalDate startDate = LocalDate.now(ClockConfig.ZONE).plusDays(1);
        return Campaign.create(
                ownerId, storeId,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, startDate, startDate.plusDays(3),
                LocalDateTime.now());
    }

    private Campaign activeCampaign(Long ownerId, Long storeId) {
        Campaign campaign = draft(ownerId, storeId);
        campaign.schedule(LocalDateTime.now());
        campaign.activate(LocalDateTime.now());
        return campaign;
    }

    private Campaign activeCampaignWithMenu(Long ownerId, Long storeId, Long targetMenuId) {
        LocalDate startDate = LocalDate.now(ClockConfig.ZONE).plusDays(1);
        Campaign campaign = Campaign.create(
                ownerId, storeId,
                DiscountTargetType.MENU, DiscountType.PERCENT, 10, targetMenuId,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, startDate, startDate.plusDays(3),
                LocalDateTime.now());
        campaign.schedule(LocalDateTime.now());
        campaign.activate(LocalDateTime.now());
        return campaign;
    }

    private Campaign pausedByOwnerCampaign(Long ownerId, Long storeId) {
        Campaign campaign = activeCampaign(ownerId, storeId);
        campaign.pause(PauseReason.OWNER, LocalDateTime.now());
        return campaign;
    }
}
