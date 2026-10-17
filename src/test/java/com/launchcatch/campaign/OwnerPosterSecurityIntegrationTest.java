package com.launchcatch.campaign;

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

    private final List<Long> createdStoreIds = new ArrayList<>();
    private final List<Long> createdOwnerIds = new ArrayList<>();
    private final List<Long> createdAdminIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdStoreIds.forEach(storeId -> jdbc.update("delete from campaign where store_id = ?", storeId));
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
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

    private String path(long campaignId) {
        return "/v1/owner/campaigns/" + campaignId + "/poster:prepareUpload";
    }

    private Cookie ownerToken(Long ownerId) {
        return new Cookie("accessToken", jwt.createAccessToken(ownerId, Role.OWNER, Map.of("issuanceVersion", "0")));
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
                loginId, "test-hash", "owner-poster-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("accessToken", jwt.createAccessToken(adminId, role));
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
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now());
    }
}
