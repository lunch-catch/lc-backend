package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
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
 * POST /v1/owner/campaigns 의 보안 체인을 실제 입구부터 확인한다.
 *
 * 전에는 @SpringBootTest(classes = ...) 로 보안 설정과 컨트롤러만 담고 서비스를 목으로 바꾼
 * 슬라이스였다. 그 모양은 통합 테스트가 아니고(LLM 구현 플로우 5단계) 컨텍스트를 하나 더
 * 띄웠다(#278).
 *
 * 전체를 띄우면 점주 AT 검증(OwnerAccessTokenPolicy)이 계정 존재와 발급 순번까지 확인해서,
 * 점주 역할의 토큰은 실제 행을 준비해야 유효하다. 새로 만든 점주는 발급 순번이 항상 0이라
 * 그 값을 그대로 클레임에 싣는다. 관리자 역할도 같은 이유로 실제 행이 필요하다(owner 도메인의
 * OwnerProfileSecurityIntegrationTest와 같은 이유).
 *
 * AbstractCampaignIntegrationTest의 클래스 단위 @Transactional을 NOT_SUPPORTED로 덮어쓴다.
 * 그 안에 두면 여기서 만든 점주·관리자 행이 커밋 전이라, 점주 AT 검증의 조회(REQUIRES_NEW로
 * 새 연결을 쓴다)가 그 행을 보지 못해 유효한 토큰도 401로 떨어진다. owner/admin 도메인의
 * Abstract*IntegrationTest에는 애초에 @Transactional이 없어 같은 문제가 없다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignCouponConditionSecurityIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final String PATH = "/v1/owner/campaigns";
    private static final String VERSION_CLAIM = "issuanceVersion";
    private static final String VALID_BODY = """
            {
              "storeId": %d,
              "discountTargetType": "ALL",
              "discountType": "AMOUNT",
              "discountValue": 15,
              "issueQuantity": 100,
              "usableStartTime": "11:30",
              "usableEndTime": "13:00"
            }
            """;
    private static final String MENU_BODY = """
            {
              "storeId": %d,
              "discountTargetType": "MENU",
              "discountType": "AMOUNT",
              "discountValue": 1500,
              "targetMenuId": %d,
              "issueQuantity": 100,
              "usableStartTime": "11:30",
              "usableEndTime": "13:00"
            }
            """;
    private static final String MISSING_DISCOUNT_VALUE_BODY = """
            {
              "storeId": 17,
              "discountTargetType": "ALL",
              "discountType": "PERCENT",
              "issueQuantity": 100,
              "usableStartTime": "11:30",
              "usableEndTime": "13:00"
            }
            """;

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdStoreIds = new ArrayList<>();
    private final List<Long> createdOwnerIds = new ArrayList<>();
    private final List<Long> createdAdminIds = new ArrayList<>();

    /*
     * 클래스 단위 @Transactional을 NOT_SUPPORTED로 덮었으니 여기서 만든 행은 바로 커밋된다.
     * 공용 컨테이너 DB에 남으면 같은 JVM에서 도는 다른 캠페인 통합 테스트의 조회에 섞여
     * 들어갈 수 있어 직접 지운다(코드래빗 리뷰로 찾음, unit-testing-guideline.md UT-6-02).
     */
    @AfterEach
    void tearDown() {
        createdStoreIds.forEach(storeId -> {
            jdbc.update("delete from campaign where store_id = ?", storeId);
            jdbc.update("delete from store_menu where store_id = ?", storeId);
            jdbc.update("delete from store where store_id = ?", storeId);
        });
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
    }

    @Test
    void 가게에_DRAFT가_없으면_201을_반환한다() throws Exception {
        Long ownerId = createActiveOwner();
        long storeId = createFinalizedStore(ownerId);

        mvc.perform(post(PATH)
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(storeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.campaignId").exists())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void 가게에_DRAFT가_있으면_200을_반환한다() throws Exception {
        Long ownerId = createActiveOwner();
        long storeId = createFinalizedStore(ownerId);
        Long existingId = campaignRepository.saveAndFlush(draft(ownerId, storeId)).getId();

        mvc.perform(post(PATH)
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(storeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.campaignId").value(existingId.intValue()));
    }

    /*
     * 요청의 storeId가 로그인한 점주의 가게인지 실제 store 행으로 확인한다(#874). 다른 점주의 가게로는
     * 캠페인이 만들어지지 않고 그 가게의 DRAFT도 바뀌지 않아야 한다.
     */
    @Test
    void 다른_점주의_가게로는_403이고_캠페인을_만들지_않는다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        long otherStoreId = createFinalizedStore(otherOwnerId);

        mvc.perform(post(PATH)
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(otherStoreId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-013"));

        // findByStoreIdAndStatus 는 행 잠금 조회라 이 클래스(트랜잭션 없음)에서는 쓸 수 없어 건수만 센다.
        Integer count = jdbc.queryForObject("select count(*) from campaign where store_id = ?", Integer.class, otherStoreId);
        assertThat(count).isZero();
    }

    @Test
    void 다른_점주의_가게에_DRAFT가_있어도_덮어쓰지_않는다() throws Exception {
        Long ownerId = createActiveOwner();
        Long otherOwnerId = createActiveOwner();
        long otherStoreId = createFinalizedStore(otherOwnerId);
        Long otherDraftId = campaignRepository.saveAndFlush(draft(otherOwnerId, otherStoreId)).getId();

        mvc.perform(post(PATH)
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(otherStoreId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-013"));

        Campaign unchanged = campaignRepository.findById(otherDraftId).orElseThrow();
        assertThat(unchanged.getOwnerId()).isEqualTo(otherOwnerId);
        assertThat(unchanged.getDiscountValue()).isEqualTo(10);
    }

    @Test
    void 존재하지_않는_가게도_같은_403이다() throws Exception {
        Long ownerId = createActiveOwner();

        mvc.perform(post(PATH)
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(System.nanoTime())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-013"));
    }

    @Test
    void 이미지가_검증되지_않은_메뉴는_422로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();
        long storeId = createFinalizedStore(ownerId);
        long menuId = createMenuWithoutImage(storeId);

        mvc.perform(post(PATH)
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MENU_BODY.formatted(storeId, menuId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-014"));
    }

    @Test
    void 다른_점주_가게의_메뉴는_422로_거부한다() throws Exception {
        Long ownerId = createActiveOwner();
        long storeId = createFinalizedStore(ownerId);
        Long otherOwnerId = createActiveOwner();
        long otherStoreId = createFinalizedStore(otherOwnerId);
        long otherMenuId = createMenuWithoutImage(otherStoreId);

        mvc.perform(post(PATH)
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MENU_BODY.formatted(storeId, otherMenuId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMPAIGN-014"));
    }

    @Test
    void 필수값이_빠지면_400이고_서비스를_부르지_않는다() throws Exception {
        Long ownerId = createActiveOwner();

        mvc.perform(post(PATH)
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MISSING_DISCOUNT_VALUE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
        verifyNoInteractions(campaignCouponConditionService);
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY.formatted(17)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(campaignCouponConditionService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(post(PATH)
                        .cookie(nonOwnerToken(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(17)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
        verifyNoInteractions(campaignCouponConditionService);
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
                loginId, "test-hash", "campaign-security-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("ownerAccessToken", jwt.createAccessToken(adminId, role));
    }

    private Long createActiveOwner() {
        String email = "campaign-security-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ACTIVE', ?, ?)", email, now, now);
        Long ownerId = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
        createdOwnerIds.add(ownerId);
        return ownerId;
    }

    /*
     * 점주 한 명의 최종 등록된 가게 행을 만든다. 최종 등록 제약(chk_store_finalized)이 사업자 검증과 좌표를
     * 요구하므로 함께 채운다. store 도메인 내부를 직접 참조하지 않고 canManageStore 가 보는 행만 준비한다.
     */
    private long createFinalizedStore(Long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into store (owner_id, name, category_code, representative_name, "
                        + "business_verification_status, business_verified_at, latitude, longitude, finalized, "
                        + "finalized_at, created_at, updated_at) "
                        + "values (?, ?, 'KOREAN', ?, 'VERIFIED', ?, 37.5, 127.0, true, ?, ?, ?)",
                ownerId, "campaign-security-test-store-" + System.nanoTime(), "대표자", now, now, now, now);
        Long storeId = jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
        createdStoreIds.add(storeId);
        return storeId;
    }

    private long createMenuWithoutImage(long storeId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into store_menu (store_id, name, price, sort_order, created_at, updated_at) "
                + "values (?, ?, 8000, 1, ?, ?)", storeId, "campaign-security-test-menu-" + System.nanoTime(), now, now);
        return jdbc.queryForObject("select store_menu_id from store_menu where store_id = ?", Long.class, storeId);
    }

    private Campaign draft(Long ownerId, long storeId) {
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
