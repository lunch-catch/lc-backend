package com.lunchcatch.campaign;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.DiscountTargetType;
import com.lunchcatch.campaign.contract.DiscountType;
import com.lunchcatch.campaign.contract.TargetGender;
import com.lunchcatch.campaign.entity.Campaign;
import com.lunchcatch.campaign.poster.entity.Poster;
import com.lunchcatch.campaign.poster.repository.PosterRepository;
import com.lunchcatch.campaign.template.entity.Template;
import com.lunchcatch.campaign.template.repository.TemplateRepository;
import jakarta.servlet.http.Cookie;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * POST /v1/owner/campaigns/{campaignId}/poster:prepareUpload(#376)의 보안 체인을 실제
 * 입구부터 확인한다. CampaignTargetAudienceSecurityIntegrationTest와 같은 이유로 실제
 * 점주·관리자 행을 준비하고 클래스 단위 @Transactional을 NOT_SUPPORTED로 덮는다
 * (feedback_campaign_integration_test_transactional_pitfall).
 *
 * S3 호출까지 가는 성공 경로는 PosterUploadServiceTest(단위)가 이미 덮어서, 여기서는
 * 캠페인 소유권·상태와 역할만 본다. 이 테스트가 다루는 실패들은 모두 S3를 부르기 전에
 * 걸려서 S3Presigner/S3Client를 따로 목으로 바꾸지 않는다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnerPosterSecurityIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String VALID_BODY = """
            {
              "contentType": "image/jpeg",
              "fileSize": 204800
            }
            """;

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
        // poster가 campaign을 FK로 참조해, campaign을 지우기 전에 먼저 지워야 한다.
        createdStoreIds.forEach(storeId -> jdbc.update(
                "delete from poster where campaign_id in (select campaign_id from campaign where store_id = ?)",
                storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from campaign where store_id = ?", storeId));
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
        createdTemplateIds.forEach(templateId -> jdbc.update("delete from template where template_id = ?", templateId));
    }

    @Test
    void 본인_가게의_캠페인이_아니면_403이다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(otherOwnerId));

        mvc.perform(post(path(campaign.getId()))
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void 캠페인이_없으면_403이다() throws Exception {
        Long ownerId = createActiveOwner();

        mvc.perform(post(path(999_999_999L))
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void DRAFT가_아니면_409이다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));
        campaign.schedule(LocalDateTime.now());
        campaignRepository.saveAndFlush(campaign);

        mvc.perform(post(path(campaign.getId()))
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-006"));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(post(path(1L)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(post(path(1L))
                        .cookie(nonOwnerToken(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void GET_본인_가게의_캠페인이_아니면_403이다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(otherOwnerId));

        mvc.perform(get(getPath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void GET_캠페인이_없으면_403이다() throws Exception {
        Long ownerId = createActiveOwner();

        mvc.perform(get(getPath(999_999_999L)).cookie(ownerToken(ownerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void GET_포스터가_없으면_404다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));

        mvc.perform(get(getPath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTER-020"));
    }

    /** 점주용 조회는 캠페인 상태를 보지 않는다. DRAFT에서도 저장된 포스터를 돌려준다. */
    @Test
    void GET_포스터가_있으면_200이다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));
        Long templateId = persistTemplate();
        posterRepository.saveAndFlush(
                Poster.create(campaign.getId(), templateId, "분식집 - 가을 신메뉴", "{}", "<div>html</div>",
                        "campaigns/" + campaign.getId() + "/poster/a.jpg"));

        mvc.perform(get(getPath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaignId").value(campaign.getId()))
                .andExpect(jsonPath("$.data.templateId").value(templateId))
                .andExpect(jsonPath("$.data.html").value("<div>html</div>"));
    }

    @Test
    void GET_토큰이_없으면_401이다() throws Exception {
        mvc.perform(get(getPath(1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void GET_점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(get(getPath(1L)).cookie(nonOwnerToken(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    private static final String EDIT_HTML = "<div><h1 data-slot=\"eventName\" style=\"font-size:20px\">분식집</h1>"
            + "<p data-slot=\"discount\">1000원 할인</p><p data-slot=\"period\">p</p>"
            + "<img data-slot=\"image\" src=\"x\"></div>";
    private static final String EDIT_SLOTS = "{\"eventName\":{\"type\":\"text\",\"value\":\"분식집\"},"
            + "\"discount\":{\"type\":\"text\",\"value\":\"1000원 할인\"},"
            + "\"period\":{\"type\":\"text\",\"value\":\"p\"},\"image\":{\"type\":\"image\",\"value\":\"x\"}}";
    private static final String EDIT_BODY = """
            {
              "eventName": {"text": "점심 특가", "fontSizePx": 32},
              "discount": {"suffix": "오늘만", "fontSizePx": 26}
            }
            """;

    @Test
    void PATCH_본인_DRAFT_캠페인의_포스터를_고친다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));
        posterRepository.saveAndFlush(Poster.create(campaign.getId(), persistTemplate(), "분식집 - 가을 신메뉴",
                EDIT_SLOTS, EDIT_HTML, "campaigns/" + campaign.getId() + "/poster/a.jpg"));

        mvc.perform(patch(getPath(campaign.getId())).cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slots.eventName.text").value("점심 특가"))
                .andExpect(jsonPath("$.data.slots.eventName.fontSizePx").value(32))
                .andExpect(jsonPath("$.data.slots.discount.baseText").value("1000원 할인"))
                .andExpect(jsonPath("$.data.slots.discount.suffix").value("오늘만"))
                .andExpect(jsonPath("$.data.slots.discount.fontSizePx").value(26));
        mvc.perform(get(getPath(campaign.getId())).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slots.eventName.text").value("점심 특가"));
    }

    @Test
    void PATCH_범위를_벗어난_글자_크기는_422다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));
        posterRepository.saveAndFlush(Poster.create(campaign.getId(), persistTemplate(), "분식집 - 가을 신메뉴",
                EDIT_SLOTS, EDIT_HTML, "campaigns/" + campaign.getId() + "/poster/a.jpg"));

        mvc.perform(patch(getPath(campaign.getId())).cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventName\": {\"fontSizePx\": 99}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POSTER-027"));
    }

    @Test
    void PATCH_포스터가_없으면_404다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));

        mvc.perform(patch(getPath(campaign.getId())).cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTER-020"));
    }

    @Test
    void PATCH_본인_캠페인이_아니면_403이다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(otherOwnerId));

        mvc.perform(patch(getPath(campaign.getId())).cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void PATCH_DRAFT가_아니면_409이다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));
        campaign.schedule(LocalDateTime.now());
        campaignRepository.saveAndFlush(campaign);

        mvc.perform(patch(getPath(campaign.getId())).cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-006"));
    }

    @Test
    void PATCH_토큰이_없으면_401이다() throws Exception {
        mvc.perform(patch(getPath(1L)).contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void PATCH_점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(patch(getPath(1L)).cookie(nonOwnerToken(role))
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    private String path(long campaignId) {
        return "/v1/owner/campaigns/" + campaignId + "/poster:prepareUpload";
    }

    private String getPath(long campaignId) {
        return "/v1/owner/campaigns/" + campaignId + "/poster";
    }

    private Long persistTemplate() {
        Template template = templateRepository.saveAndFlush(Template.createDraft("가을 신메뉴"));
        createdTemplateIds.add(template.getId());
        return template.getId();
    }

    private Cookie ownerToken(Long ownerId) {
        return new Cookie("ownerAccessToken", jwt.createAccessToken(ownerId, Role.OWNER, Map.of("issuanceVersion", "0")));
    }

    // 사용자 역할은 계정 존재를 확인하지 않아 임의의 식별자로 충분하다(owner 도메인과 같은 이유).
    private Cookie nonOwnerToken(Role role) {
        if (role == Role.MEMBER) {
            return new Cookie("ownerAccessToken", jwt.createAccessToken(1L, role));
        }
        String loginId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, "test-hash", "owner-poster-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("ownerAccessToken", jwt.createAccessToken(adminId, role));
    }

    private Long createActiveOwner() {
        String email = "owner-poster-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ACTIVE', ?, ?)", email, now, now);
        Long ownerId = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
        createdOwnerIds.add(ownerId);
        return ownerId;
    }

    private Campaign draft(Long ownerId) {
        long storeId = System.nanoTime();
        createdStoreIds.add(storeId);
        return Campaign.create(
                ownerId, storeId,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now());
    }
}
