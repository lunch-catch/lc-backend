package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.poster.client.PosterImageInspection;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.repository.TemplateRepository;
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
 * POST /v1/owner/campaigns/{campaignId}:activate의 보안 체인과 검수 결과 분기를 실제 입구부터
 * 확인한다. CampaignTargetAudienceSecurityIntegrationTest와 같은 이유로 실제 점주 행을 준비하고
 * 클래스 단위 @Transactional을 NOT_SUPPORTED로 덮는다
 * (feedback_campaign_integration_test_transactional_pitfall).
 *
 * S3·Rekognition 호출은 PosterImageInspector를 스텁해 부르지 않는다(PosterModerationIntegrationTest와
 * 같은 이유).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignActivationSecurityIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String VERSION_CLAIM = "issuanceVersion";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PosterRepository posterRepository;
    @Autowired private TemplateRepository templateRepository;

    private final List<Long> createdStoreIds = new ArrayList<>();
    private final List<Long> createdOwnerIds = new ArrayList<>();
    private final List<Long> createdAdminIds = new ArrayList<>();
    private final List<Long> createdTemplateIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // poster_moderation_result -> poster -> campaign 순으로 FK를 따라 지운다.
        createdStoreIds.forEach(storeId -> jdbc.update(
                "delete from poster_moderation_result where poster_id in "
                        + "(select poster_id from poster where campaign_id in "
                        + "(select campaign_id from campaign where store_id = ?))",
                storeId));
        createdStoreIds.forEach(storeId -> jdbc.update(
                "delete from poster where campaign_id in (select campaign_id from campaign where store_id = ?)",
                storeId));
        createdStoreIds.forEach(storeId -> jdbc.update(
                "delete from campaign_status_log where campaign_id in (select campaign_id from campaign where store_id = ?)",
                storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from campaign where store_id = ?", storeId));
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
        createdTemplateIds.forEach(templateId -> jdbc.update("delete from template where template_id = ?", templateId));
    }

    @Test
    void 검수를_통과하면_200과_SCHEDULED를_응답한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = System.nanoTime();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId, storeId));
        createPoster(campaign.getId(), "김밥천국");
        doReturn(PosterImageInspection.read(300, 300, 0.1, List.of()))
                .when(posterImageInspector).inspect(any());

        mvc.perform(post(activatePath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.campaignId").value(campaign.getId().intValue()))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.reasons").isEmpty());

        Campaign reloaded = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertThat(reloaded.getStatus().name()).isEqualTo("SCHEDULED");
    }

    @Test
    void 검수에_실패하면_200과_DRAFT와_사유를_응답한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = System.nanoTime();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId, storeId));
        createPoster(campaign.getId(), "최저가");
        doReturn(PosterImageInspection.toolFailure()).when(posterImageInspector).inspect(any());

        mvc.perform(post(activatePath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.reasons").isArray())
                .andExpect(jsonPath("$.data.reasons", org.hamcrest.Matchers.hasItem("FORBIDDEN_EXPRESSION")))
                .andExpect(jsonPath("$.data.reasons", org.hamcrest.Matchers.hasItem("MODERATION_TOOL_FAILURE")));

        Campaign reloaded = campaignRepository.findById(campaign.getId()).orElseThrow();
        assertThat(reloaded.getStatus().name()).isEqualTo("DRAFT");
    }

    @Test
    void 포스터가_없으면_404로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = System.nanoTime();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId, storeId));

        mvc.perform(post(activatePath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTER-021"));
    }

    @Test
    void DRAFT가_아니면_409로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long storeId = System.nanoTime();
        Campaign campaign = campaignRepository.saveAndFlush(activeCampaign(ownerId, storeId));

        mvc.perform(post(activatePath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-006"));
    }

    @Test
    void 본인_가게의_캠페인이_아니면_403으로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        Long otherStoreId = System.nanoTime();
        Campaign campaign = campaignRepository.saveAndFlush(draft(otherOwnerId, otherStoreId));

        mvc.perform(post(activatePath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void 캠페인이_없으면_403으로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();

        mvc.perform(post(activatePath(999_999_999L)).cookie(ownerToken(ownerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void 토큰이_없으면_401로_거부한다() throws Exception {
        mvc.perform(post(activatePath(1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403으로_거부한다(Role role) throws Exception {
        mvc.perform(post(activatePath(1L)).cookie(nonOwnerToken(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    private String activatePath(long campaignId) {
        return "/v1/owner/campaigns/" + campaignId + ":activate";
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
                loginId, "test-hash", "campaign-activate-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("accessToken", jwt.createAccessToken(adminId, role));
    }

    private Long createActiveOwner() {
        String email = "campaign-activate-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ACTIVE', ?, ?)", email, now, now);
        Long ownerId = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
        createdOwnerIds.add(ownerId);
        return ownerId;
    }

    private Poster createPoster(Long campaignId, String eventName) {
        Template template = templateRepository.saveAndFlush(Template.createDraft("가을 신메뉴"));
        createdTemplateIds.add(template.getId());
        String slotValues = "{\"eventName\":{\"type\":\"text\",\"value\":\"" + eventName + "\"},"
                + "\"image\":{\"type\":\"image\",\"value\":\"https://cdn.example.com/a.png\"}}";
        return posterRepository.saveAndFlush(Poster.create(
                campaignId, template.getId(), "김밥천국 - 가을 신메뉴", slotValues, "<div></div>",
                "posters/" + campaignId + "/image.png"));
    }

    private Campaign draft(Long ownerId, Long storeId) {
        createdStoreIds.add(storeId);
        LocalDate startDate = LocalDate.now().plusDays(1);
        return Campaign.create(
                ownerId, storeId,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
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
}
