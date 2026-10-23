package com.launchcatch.campaign;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
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
 * GET /v1/owner/templates 의 보안 체인을 실제 입구부터 확인한다.
 *
 * CampaignCouponConditionSecurityIntegrationTest와 같은 이유로 실제 점주 행을 준비하고,
 * 클래스 단위 @Transactional을 NOT_SUPPORTED로 덮어 점주 AT 검증이 다른 연결에서도 이 행을
 * 보게 한다(#244에서 확인한 함정, feedback_campaign_integration_test_transactional_pitfall).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnerTemplateListSecurityIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String PATH = "/v1/owner/templates";
    private static final String VERSION_CLAIM = "issuanceVersion";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TemplateRepository templateRepository;

    private final List<Long> createdOwnerIds = new ArrayList<>();
    private final List<Long> createdAdminIds = new ArrayList<>();
    private final List<Long> createdTemplateIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdTemplateIds.forEach(templateId -> {
            jdbc.update("delete from template_version where template_id = ?", templateId);
            jdbc.update("delete from template where template_id = ?", templateId);
        });
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
    }

    @Test
    void 게시되고_활성인_템플릿만_돌려준다() throws Exception {
        Long ownerId = createOwner("ACTIVE");
        Template older = publishedActiveTemplate("오래된 활성 템플릿");
        Template newer = publishedActiveTemplate("최신 활성 템플릿");
        jdbc.update("update template set created_at = ? where template_id = ?",
                LocalDateTime.of(2024, 1, 1, 0, 0), older.getId());
        jdbc.update("update template set created_at = ? where template_id = ?",
                LocalDateTime.of(2024, 1, 2, 0, 0), newer.getId());
        draftTemplate("임시저장 템플릿");
        publishedInactiveTemplate("비활성 템플릿");

        mvc.perform(get(PATH).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].templateId").value(newer.getId().intValue()))
                .andExpect(jsonPath("$.data[1].templateId").value(older.getId().intValue()))
                .andExpect(jsonPath("$.data[0].name").value("최신 활성 템플릿"))
                .andExpect(jsonPath("$.data[0].html").value(newer.getHtmlContent()));
    }

    @Test
    void 활성_템플릿이_없으면_빈_배열이다() throws Exception {
        Long ownerId = createOwner("ACTIVE");

        mvc.perform(get(PATH).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void ONBOARDING_상태_점주도_조회할_수_있다() throws Exception {
        Long ownerId = createOwner("ONBOARDING");

        mvc.perform(get(PATH).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk());
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(get(PATH).cookie(nonOwnerToken(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    private Cookie ownerToken(Long ownerId) {
        return new Cookie("ownerAccessToken", jwt.createAccessToken(ownerId, Role.OWNER, Map.of(VERSION_CLAIM, "0")));
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
                loginId, "test-hash", "owner-template-list-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("ownerAccessToken", jwt.createAccessToken(adminId, role));
    }

    private Long createOwner(String status) {
        String email = "owner-template-list-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', ?, ?, ?)", email, status, now, now);
        Long ownerId = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
        createdOwnerIds.add(ownerId);
        return ownerId;
    }

    private Template draftTemplate(String name) {
        Template template = Template.createDraft(name);
        template.addDraftVersion(1L, LocalDateTime.now(), "요청", "req-" + System.nanoTime(), "<div>draft</div>", 1);
        Template saved = templateRepository.saveAndFlush(template);
        createdTemplateIds.add(saved.getId());
        return saved;
    }

    private Template publishedInactiveTemplate(String name) {
        Template template = draftTemplate(name);
        template.publish("<div>" + name + "</div>", LocalDateTime.now(), 1);
        return templateRepository.saveAndFlush(template);
    }

    private Template publishedActiveTemplate(String name) {
        Template template = publishedInactiveTemplate(name);
        template.changeActive(true, 1L, LocalDateTime.now());
        return templateRepository.saveAndFlush(template);
    }
}
