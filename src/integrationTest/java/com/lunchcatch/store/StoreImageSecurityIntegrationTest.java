package com.lunchcatch.store;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.service.StoreRegistrationProgressService;
import jakarta.servlet.http.Cookie;
import java.net.URL;
import java.time.DayOfWeek;
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
 * GET /v1/owner/onboarding, POST /v1/owner/store-images:prepareUpload,
 * :confirmUpload, :complete(#375)의 보안 체인과 콜론 커스텀 메서드 경로 라우팅을 실제
 * 입구부터 확인한다. S3Presigner/S3Client는 실제 AWS를 부르지 않도록 목으로 바꾼다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoreImageSecurityIntegrationTest extends AbstractStoreIntegrationTest {

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
        // store_registration_progress가 store_id를 FK로 참조해서, store보다 먼저 지운다.
        createdOwnerIds.forEach(ownerId ->
                jdbc.update("delete from store_registration_progress where owner_id = ?", ownerId));
        createdStoreIds.forEach(storeId -> {
            jdbc.update("delete from store_image where store_id = ?", storeId);
            jdbc.update("delete from store_business_hour where store_id = ?", storeId);
            jdbc.update("delete from store where store_id = ?", storeId);
        });
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner_terms_agreement where owner_id = ?", ownerId));
        createdOwnerIds.forEach(ownerId -> jdbc.update("delete from owner where owner_id = ?", ownerId));
        createdAdminIds.forEach(adminId -> jdbc.update("delete from admin where admin_id = ?", adminId));
    }

    @Test
    void 인테리어_이미지를_올리고_확인한_뒤_이미지_단계를_완료한다() throws Exception {
        Long ownerId = createOnboardingOwner();
        Long storeId = createStore(ownerId);
        advanceToImagesStep(ownerId, storeId);

        PresignedPutObjectRequest presigned = org.mockito.Mockito.mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://example.com/presigned"));
        when(s3Presigner.presignPutObject(any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class)))
                .thenReturn(presigned);

        String progressBody = mvc.perform(get("/v1/owner/onboarding").cookie(ownerToken(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeStep").value("IMAGES"))
                .andReturn().getResponse().getContentAsString();
        long version = ((Number) JsonPath.read(progressBody, "$.data.version")).longValue();

        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1000L).build());

        // 로고가 없으면 IMAGES 단계를 끝낼 수 없다.
        mvc.perform(post("/v1/owner/store-images:complete")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE-010"));

        String logoBody = mvc.perform(post("/v1/owner/store-images:prepareUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageType":"LOGO","originalFilename":"logo.jpg","contentType":"image/jpeg","fileSize":1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sortOrder").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String logoUploadId = JsonPath.read(logoBody, "$.data.uploadId");

        // 올리기만 하고 확인하지 않은 로고로는 아직 단계를 끝낼 수 없다.
        mvc.perform(post("/v1/owner/store-images:complete")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE-010"));

        mvc.perform(post("/v1/owner/store-images:confirmUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + logoUploadId + "\"}"))
                .andExpect(status().isOk());

        // 로고를 다시 올리면 새 행 없이 교체되고(가게당 1개), 확인하기 전까지는 다시 PENDING 이다.
        mvc.perform(post("/v1/owner/store-images:prepareUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageType":"LOGO","originalFilename":"logo2.png","contentType":"image/png","fileSize":1000}
                                """))
                .andExpect(status().isOk());
        Integer logoRows = jdbc.queryForObject(
                "select count(*) from store_image where store_id = ? and image_type = 'LOGO'", Integer.class, storeId);
        org.assertj.core.api.Assertions.assertThat(logoRows).isEqualTo(1);
        String logoStatus = jdbc.queryForObject(
                "select upload_status from store_image where store_id = ? and image_type = 'LOGO'",
                String.class, storeId);
        org.assertj.core.api.Assertions.assertThat(logoStatus).isEqualTo("PENDING");

        String replacedBody = mvc.perform(post("/v1/owner/store-images:prepareUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageType":"LOGO","originalFilename":"logo3.jpg","contentType":"image/jpeg","fileSize":1000}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacedUploadId = JsonPath.read(replacedBody, "$.data.uploadId");
        mvc.perform(post("/v1/owner/store-images:confirmUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + replacedUploadId + "\"}"))
                .andExpect(status().isOk());

        String prepareBody = mvc.perform(post("/v1/owner/store-images:prepareUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageType":"INTERIOR","originalFilename":"interior.jpg","contentType":"image/jpeg","fileSize":1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sortOrder").value(1))
                .andExpect(jsonPath("$.data.uploadUrl").value("https://example.com/presigned"))
                .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(prepareBody, "$.data.uploadId");

        mvc.perform(post("/v1/owner/store-images:confirmUpload")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"" + uploadId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mvc.perform(post("/v1/owner/store-images:complete")
                        .cookie(ownerToken(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeStep").value("MENU"));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get("/v1/owner/onboarding"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(get("/v1/owner/onboarding").cookie(nonOwnerToken(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    private Cookie ownerToken(Long ownerId) {
        return new Cookie("ownerAccessToken", jwt.createAccessToken(ownerId, Role.OWNER, Map.of(VERSION_CLAIM, "0")));
    }

    private Cookie nonOwnerToken(Role role) {
        if (role == Role.MEMBER) {
            return new Cookie("ownerAccessToken", jwt.createAccessToken(1L, role));
        }
        String loginId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, "test-hash", "store-image-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        createdAdminIds.add(adminId);
        return new Cookie("ownerAccessToken", jwt.createAccessToken(adminId, role));
    }

    private Long createOnboardingOwner() {
        String email = "store-image-test-" + System.nanoTime() + "@example.com";
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
        Long storeId = jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
        createdStoreIds.add(storeId);
        return storeId;
    }

    private void advanceToImagesStep(Long ownerId, Long storeId) {
        // 진행 단계뿐 아니라 조회가 확인하는 선행 단계의 실제 데이터도 준비한다.
        jdbc.update("""
                insert into owner_terms_agreement(owner_id, required, agreed, agreed_at, created_at, updated_at)
                values (?, true, true, now(6), now(6), now(6))
                """, ownerId);
        jdbc.update("""
                update store set phone='02-123-4567', business_registration_number='1234567890',
                    business_verification_status='VERIFIED', business_verified_at=now(6),
                    kakao_place_id='test-place', road_address='테스트 도로명 주소', latitude=37.5, longitude=127.1
                where store_id=?
                """, storeId);
        for (DayOfWeek day : DayOfWeek.values()) {
            jdbc.update("""
                    insert into store_business_hour(store_id, day_of_week, closed, created_at, updated_at)
                    values (?, ?, true, now(6), now(6))
                    """, storeId, day.name());
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            progressService.initialize(ownerId);
            progressService.completeStep(ownerId, 0, StoreRegistrationStep.TERMS, null);
            progressService.completeStep(ownerId, 1, StoreRegistrationStep.STORE_INFO, storeId);
            progressService.completeStep(ownerId, 2, StoreRegistrationStep.LOCATION, null);
            progressService.completeStep(ownerId, 3, StoreRegistrationStep.HOURS, null);
        });
    }
}
