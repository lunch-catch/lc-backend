package com.launchcatch.store;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.service.StoreRegistrationProgressService;
import jakarta.servlet.http.Cookie;
import java.net.URL;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/*
 * POST /v1/owner/store-menus, /v1/owner/store-menu-images:prepareUpload, :confirmUpload,
 * /v1/owner/store-menus:complete(#389)의 보안 체인과 실제 입구부터의 전체 흐름을 확인한다.
 * S3Presigner/S3Client는 실제 AWS를 부르지 않도록 목으로 바꾼다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoreMenuSecurityIntegrationTest extends AbstractStoreIntegrationTest {

    private static final String VERSION_CLAIM = "issuanceVersion";

    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StoreRegistrationProgressService progressService;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> createdOwnerIds = new ArrayList<>();
    private final List<Long> createdStoreIds = new ArrayList<>();
    private final List<Long> createdAdminIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdOwnerIds.forEach(ownerId ->
                jdbc.update("delete from store_registration_progress where owner_id = ?", ownerId));
        createdStoreIds.forEach(storeId -> {
            jdbc.update("delete from store_menu_image where store_menu_id in "
                    + "(select store_menu_id from store_menu where store_id = ?)", storeId);
            jdbc.update("delete from store_menu where store_id = ?", storeId);
            jdbc.update("delete from store_image where store_id = ?", storeId);
            jdbc.update("delete from store where store_id = ?", storeId);
        });
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
    }

    @Test
    void 메뉴를_등록하고_이미지를_올려_확인한_뒤_메뉴_단계를_완료한다() throws Exception {
        Long ownerId = createOnboardingOwner();
        Long storeId = createStore(ownerId);
        createdStoreIds.add(storeId);
        advanceToMenuStep(ownerId, storeId);

        String progressBody = mvc.perform(get("/v1/owner/store-registration-progress").cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeStep").value("MENU"))
                .andReturn().getResponse().getContentAsString();
        long version = ((Number) JsonPath.read(progressBody, "$.data.version")).longValue();

        String registerBody = mvc.perform(post("/v1/owner/store-menus")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"제육볶음","price":9000,"description":"매콤합니다"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sortOrder").value(1))
                .andReturn().getResponse().getContentAsString();
        long menuId = ((Number) JsonPath.read(registerBody, "$.data.menuId")).longValue();

        PresignedPutObjectRequest presigned = org.mockito.Mockito.mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://example.com/presigned"));
        when(s3Presigner.presignPutObject(any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class)))
                .thenReturn(presigned);

        String prepareBody = mvc.perform(post("/v1/owner/store-menu-images:prepareUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuId\":" + menuId
                                + ",\"originalFilename\":\"menu.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadUrl").value("https://example.com/presigned"))
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(prepareBody, "$.data.uploadId");

        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1000L).build());

        mvc.perform(post("/v1/owner/store-menu-images:confirmUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mvc.perform(post("/v1/owner/store-menus:complete")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeStep").value("MENU"))
                .andExpect(jsonPath("$.data.version").value(version + 1));
    }

    @Test
    void 메뉴_없이_단계를_완료해도_버전이_오른다() throws Exception {
        Long ownerId = createOnboardingOwner();
        Long storeId = createStore(ownerId);
        advanceToMenuStep(ownerId, storeId);
        createdStoreIds.add(storeId);

        String progressBody = mvc.perform(get("/v1/owner/store-registration-progress").cookie(ownerToken(ownerId)))
                .andReturn().getResponse().getContentAsString();
        long version = ((Number) JsonPath.read(progressBody, "$.data.version")).longValue();

        mvc.perform(post("/v1/owner/store-menus:complete")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(version + 1));
    }

    @Test
    void 다른_가게의_메뉴에는_이미지를_올릴_수_없다() throws Exception {
        Long ownerId = createOnboardingOwner();
        Long storeId = createStore(ownerId);
        advanceToMenuStep(ownerId, storeId);
        createdStoreIds.add(storeId);

        Long otherOwnerId = createOnboardingOwner();
        Long otherStoreId = createStore(otherOwnerId);
        advanceToMenuStep(otherOwnerId, otherStoreId);
        createdStoreIds.add(otherStoreId);
        String otherRegisterBody = mvc.perform(post("/v1/owner/store-menus")
                        .cookie(ownerToken(otherOwnerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"다른 가게 메뉴","price":5000}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long otherMenuId = ((Number) JsonPath.read(otherRegisterBody, "$.data.menuId")).longValue();

        mvc.perform(post("/v1/owner/store-menu-images:prepareUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"menuId\":" + otherMenuId
                                + ",\"originalFilename\":\"menu.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":1000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE-012"));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(post("/v1/owner/store-menus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"제육볶음","price":9000}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(post("/v1/owner/store-menus")
                        .cookie(nonOwnerToken(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"제육볶음","price":9000}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    private Cookie ownerToken(Long ownerId) {
        return new Cookie("accessToken", jwt.createAccessToken(ownerId, Role.OWNER, Map.of(VERSION_CLAIM, "0")));
    }

    private Cookie nonOwnerToken(Role role) {
        if (role == Role.MEMBER) {
            return new Cookie("accessToken", jwt.createAccessToken(1L, role));
        }
        String loginId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, "test-hash", "store-menu-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("accessToken", jwt.createAccessToken(adminId, role));
    }

    private Long createOnboardingOwner() {
        String email = "store-menu-test-" + System.nanoTime() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, status, created_at, updated_at) "
                + "values (?, 'test-hash', 'ONBOARDING', ?, ?)", email, now, now);
        Long ownerId = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
        createdOwnerIds.add(ownerId);
        return ownerId;
    }

    private Long createStore(Long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into store (owner_id, name, category_code, representative_name, created_at, updated_at) "
                + "values (?, '테스트 가게', 'KOREAN', '대표자', ?, ?)", ownerId, now, now);
        return jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
    }

    private void advanceToMenuStep(Long ownerId, Long storeId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            progressService.initialize(ownerId);
            progressService.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null);
            progressService.completeStep(ownerId, 1, StoreRegistrationStep.STORE_INFO, storeId);
            progressService.completeStep(ownerId, 2, StoreRegistrationStep.LOCATION, null);
            progressService.completeStep(ownerId, 3, StoreRegistrationStep.HOURS, null);
            progressService.completeStep(ownerId, 4, StoreRegistrationStep.IMAGES, null);
        });
    }
}
