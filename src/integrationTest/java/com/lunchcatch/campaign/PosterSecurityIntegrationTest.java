package com.lunchcatch.campaign;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
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
 * GET /v1/campaigns/{campaignId}/poster(#495)의 보안 체인과 캠페인 상태에 따른 공개 범위를
 * 실제 입구부터 확인한다. 캠페인 상태별 판정 자체는 PosterQueryServiceTest(단위)가 이미
 * 덮어서, 여기서는 역할 경계와 실제 라우팅·DB 왕복이 맞물려 동작하는지만 본다
 * (feedback_campaign_integration_test_transactional_pitfall).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PosterSecurityIntegrationTest extends AbstractCampaignIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PosterRepository posterRepository;
    @Autowired private TemplateRepository templateRepository;

    private final List<Long> createdStoreIds = new ArrayList<>();
    private final List<Long> createdTemplateIds = new ArrayList<>();
    private final List<Long> createdOwnerIds = new ArrayList<>();
    private final List<Long> createdAdminIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdStoreIds.forEach(storeId -> jdbc.update(
                "delete from poster where campaign_id in (select campaign_id from campaign where store_id = ?)",
                storeId));
        createdStoreIds.forEach(storeId -> jdbc.update("delete from campaign where store_id = ?", storeId));
        createdTemplateIds.forEach(templateId -> jdbc.update("delete from template where template_id = ?", templateId));
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
    }

    @Test
    void ACTIVE_캠페인의_포스터는_조회된다() throws Exception {
        Campaign campaign = campaignRepository.saveAndFlush(active());
        Long templateId = persistTemplate();
        posterRepository.saveAndFlush(
                Poster.create(campaign.getId(), templateId, "분식집 - 가을 신메뉴", "{}", "<div>html</div>",
                        "campaigns/" + campaign.getId() + "/poster/a.jpg"));

        mvc.perform(get(path(campaign.getId())).cookie(memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaignId").value(campaign.getId()))
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.html").value("<div>html</div>"))
                .andExpect(header().string("Cache-Control", "max-age=86400, private, immutable"));
    }

    /*
     * 실패 응답은 캐시하지 않는다. Spring Security가 기본으로 붙이는
     * "no-cache, no-store, max-age=0, must-revalidate"가 그대로 남아야 한다. 짧게 뒤에
     * 포스터가 생겨도 캐시된 404가 가리면 안 된다.
     */
    @Test
    void ACTIVE_캠페인이라도_포스터가_없으면_404다() throws Exception {
        Campaign campaign = campaignRepository.saveAndFlush(active());

        mvc.perform(get(path(campaign.getId())).cookie(memberToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTER-021"))
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatusFixture.class)
    void DRAFT나_SCHEDULED_캠페인은_비공개다(CampaignStatusFixture fixture) throws Exception {
        Campaign campaign = campaignRepository.saveAndFlush(fixture.build(this));

        mvc.perform(get(path(campaign.getId())).cookie(memberToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTER-021"));
    }

    @Test
    void 캠페인이_없으면_같은_404다() throws Exception {
        mvc.perform(get(path(999_999_999L)).cookie(memberToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POSTER-021"));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get(path(1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "ADMIN", "SUPER_ADMIN"})
    void 사용자가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(get(path(1L)).cookie(nonMemberToken(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    private String path(long campaignId) {
        return "/v1/campaigns/" + campaignId + "/poster";
    }

    private Cookie memberToken() {
        return new Cookie("accessToken", jwt.createAccessToken(1L, Role.MEMBER));
    }

    // OWNER, ADMIN, SUPER_ADMIN은 계정 존재를 확인해 실제 행이 있어야 인증을 통과한다
    // (OwnerPosterSecurityIntegrationTest와 같은 이유).
    private Cookie nonMemberToken(Role role) {
        if (role == Role.OWNER) {
            Long ownerId = createActiveOwner();
            return new Cookie("accessToken", jwt.createAccessToken(ownerId, Role.OWNER));
        }
        String loginId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, "test-hash", "poster-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("accessToken", jwt.createAccessToken(adminId, role));
    }

    private Long createActiveOwner() {
        String email = "poster-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ACTIVE', ?, ?)", email, now, now);
        Long ownerId = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
        createdOwnerIds.add(ownerId);
        return ownerId;
    }

    private Long persistTemplate() {
        Template template = templateRepository.saveAndFlush(Template.createDraft("가을 신메뉴"));
        createdTemplateIds.add(template.getId());
        return template.getId();
    }

    private Campaign draftWithPeriod() {
        long storeId = System.nanoTime();
        createdStoreIds.add(storeId);
        return Campaign.create(
                1L, storeId,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.now());
    }

    private Campaign scheduled() {
        Campaign campaign = draftWithPeriod();
        campaign.schedule(LocalDateTime.now());
        return campaign;
    }

    private Campaign active() {
        Campaign campaign = scheduled();
        campaign.activate(LocalDateTime.now());
        return campaign;
    }

    /** DRAFT, SCHEDULED 둘 다 비공개임을 확인한다. */
    private enum CampaignStatusFixture {
        DRAFT {
            Campaign build(PosterSecurityIntegrationTest test) {
                return test.draftWithPeriod();
            }
        },
        SCHEDULED {
            Campaign build(PosterSecurityIntegrationTest test) {
                return test.scheduled();
            }
        };

        abstract Campaign build(PosterSecurityIntegrationTest test);
    }
}
