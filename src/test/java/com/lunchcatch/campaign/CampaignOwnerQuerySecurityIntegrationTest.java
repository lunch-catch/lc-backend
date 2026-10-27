package com.lunchcatch.campaign;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.DiscountTargetType;
import com.lunchcatch.campaign.contract.DiscountType;
import com.lunchcatch.campaign.contract.TargetGender;
import com.lunchcatch.campaign.entity.Campaign;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * GET /v1/owner/campaigns, GET /v1/owner/campaigns/{campaignId}(#341)의 보안 체인을 실제
 * 입구부터 확인한다. CampaignTargetAudienceSecurityIntegrationTest와 같은 이유로 클래스
 * 단위 @Transactional을 NOT_SUPPORTED로 덮는다(feedback_campaign_integration_test_transactional_pitfall).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignOwnerQuerySecurityIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String VERSION_CLAIM = "issuanceVersion";

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
    void 목록_조회는_본인_소유_캠페인만_반환한다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        Campaign mine = campaignRepository.saveAndFlush(draft(ownerId));
        campaignRepository.saveAndFlush(draft(otherOwnerId));

        mvc.perform(get("/v1/owner/campaigns").cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].campaignId").value(mine.getId().intValue()));
    }

    @Test
    void 상세_조회는_등록_필드를_모두_반환한다() throws Exception {
        Long ownerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(ownerId));

        mvc.perform(get("/v1/owner/campaigns/" + campaign.getId()).cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaignId").value(campaign.getId().intValue()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.discountTargetType").value("ALL"))
                .andExpect(jsonPath("$.data.discountType").value("AMOUNT"))
                .andExpect(jsonPath("$.data.discountValue").value(10))
                .andExpect(jsonPath("$.data.targetRadius").value(1000))
                .andExpect(jsonPath("$.data.targetGender").value("ALL"))
                .andExpect(jsonPath("$.data.targetAgeGroups[0]").value("AGE_20S"))
                .andExpect(jsonPath("$.data.dailyBudget").doesNotExist());
    }

    @Test
    void 본인_가게의_캠페인이_아니면_403이다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        Campaign campaign = campaignRepository.saveAndFlush(draft(otherOwnerId));

        mvc.perform(get("/v1/owner/campaigns/" + campaign.getId()).cookie(ownerToken(ownerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void 캠페인이_없으면_403이다() throws Exception {
        Long ownerId = createActiveOwner();

        mvc.perform(get("/v1/owner/campaigns/999999999").cookie(ownerToken(ownerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-005"));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get("/v1/owner/campaigns"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(get("/v1/owner/campaigns").cookie(nonOwnerToken(role)))
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
                loginId, "test-hash", "campaign-owner-query-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("ownerAccessToken", jwt.createAccessToken(adminId, role));
    }

    private Long createActiveOwner() {
        String email = "campaign-owner-query-test-" + System.nanoTime() + "@example.com";
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
