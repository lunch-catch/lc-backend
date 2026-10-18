package com.launchcatch.store;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.store.service.StoreRegistrationProgressQueryService;
import jakarta.servlet.http.Cookie;
import java.time.DayOfWeek;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoreOnboardingStatusIntegrationTest extends AbstractStoreIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired StoreRegistrationProgressQueryService service;
    @Autowired JwtTokenProvider jwt;
    private Long ownerId;
    private final List<Long> ownerIds = new ArrayList<>();

    @BeforeEach
    void prepareOwner() {
        String email = UUID.randomUUID() + "@onboarding.test";
        jdbc.update("""
                insert into owner(email, password_hash, status, created_at, updated_at)
                values (?, 'hash', 'ONBOARDING', now(6), now(6))
                """, email);
        ownerId = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
        ownerIds.add(ownerId);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : ownerIds) {
            jdbc.update("delete from store_menu_image where store_menu_id in (select store_menu_id from store_menu where store_id in (select store_id from store where owner_id=?))", id);
            jdbc.update("delete from store_menu where store_id in (select store_id from store where owner_id=?)", id);
            jdbc.update("delete from store_image where store_id in (select store_id from store where owner_id=?)", id);
            jdbc.update("delete from store_business_hour where store_id in (select store_id from store where owner_id=?)", id);
            jdbc.update("delete from store_registration_progress where owner_id=?", id);
            jdbc.update("delete from owner_terms_agreement where owner_id=?", id);
            jdbc.update("delete from store where owner_id=?", id);
            jdbc.update("delete from owner where owner_id=?", id);
        }
    }

    @Test
    void 실제_저장값으로_진행_단계와_등록_가능_여부를_조회하고_진행_상태는_변경하지_않는다() {
        Long storeId = draft();
        progress(storeId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("TERMS");
        terms();

        jdbc.update("update store set phone=' ' where store_id=?", storeId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("STORE_INFO");
        jdbc.update("update store set phone='02-123-4567', business_verification_status='FAILED' where store_id=?", storeId);
        assertThat(service.getProgress(ownerId).canFinalize()).isFalse();
        jdbc.update("update store set business_verification_status='VERIFIED' where store_id=?", storeId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("LOCATION");

        location(storeId);
        jdbc.update("update store set kakao_place_id=null where store_id=?", storeId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("LOCATION");
        jdbc.update("update store set kakao_place_id='place', road_address=' ' where store_id=?", storeId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("LOCATION");
        jdbc.update("update store set road_address='도로명 주소' where store_id=?", storeId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("HOURS");

        hours(storeId);
        jdbc.update("delete from store_business_hour where store_id=? and day_of_week='SUNDAY'", storeId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("HOURS");
        jdbc.update("""
                insert into store_business_hour(store_id, day_of_week, closed, created_at, updated_at)
                values (?, 'SUNDAY', true, now(6), now(6))
                """, storeId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("IMAGES");

        logo(storeId);
        var response = service.getProgress(ownerId);
        assertThat(response.resumeStep()).isEqualTo("MENU");
        assertThat(response.canFinalize()).isTrue();
        assertThat(response.steps().getLast().completed()).isFalse();
        assertThat(response.version()).isEqualTo(8);
        service.getProgress(ownerId);
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id = ?",
                Long.class, ownerId)).isEqualTo(8L);
        assertThat(jdbc.queryForObject("select resume_step from store_registration_progress where owner_id = ?",
                String.class, ownerId)).isEqualTo("MENU");

        jdbc.update("update owner_terms_agreement set withdrawn_at=now(6) where owner_id=?", ownerId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("TERMS");
        assertThat(service.getProgress(ownerId).canFinalize()).isFalse();
    }

    @Test
    void 검증되지_않은_대표와_내부_이미지는_미완료다() {
        Long storeId = ready();
        jdbc.update("update store_image set upload_status='PENDING' where store_id=?", storeId);
        assertThat(service.getProgress(ownerId).resumeStep()).isEqualTo("IMAGES");
        jdbc.update("update store_image set upload_status='VERIFIED' where store_id=?", storeId);
        jdbc.update("""
                insert into store_image(store_id, image_type, object_key, upload_status, sort_order, created_at, updated_at)
                values (?, 'INTERIOR', ?, 'PENDING', 1, now(6), now(6))
                """, storeId, UUID.randomUUID().toString());
        assertThat(service.getProgress(ownerId).canFinalize()).isFalse();
        jdbc.update("update store_image set upload_status='VERIFIED', verified_at=now(6) where store_id=?", storeId);
        assertThat(service.getProgress(ownerId).canFinalize()).isTrue();
    }

    @Test
    void 메뉴는_선택이지만_있으면_이름과_가격과_검증된_이미지가_필수다() {
        Long storeId = ready();
        jdbc.update("""
                insert into store_menu(store_id, name, price, sort_order, created_at, updated_at)
                values (?, '메뉴', 10000, 1, now(6), now(6))
                """, storeId);
        assertThat(service.getProgress(ownerId).canFinalize()).isFalse();
        Long menuId = jdbc.queryForObject("select store_menu_id from store_menu where store_id=?", Long.class, storeId);
        jdbc.update("""
                insert into store_menu_image(store_menu_id, object_key, upload_status, verified_at, created_at, updated_at)
                values (?, ?, 'PENDING', null, now(6), now(6))
                """, menuId, UUID.randomUUID().toString());
        assertThat(service.getProgress(ownerId).canFinalize()).isFalse();
        jdbc.update("update store_menu_image set upload_status='VERIFIED', verified_at=now(6) where store_menu_id=?", menuId);
        assertThat(service.getProgress(ownerId).canFinalize()).isTrue();
        assertThat(service.getProgress(ownerId).steps().getLast().completed()).isTrue();
        jdbc.update("update store_menu set name=' ' where store_menu_id=?", menuId);
        assertThat(service.getProgress(ownerId).canFinalize()).isFalse();
    }

    @Test
    void ACTIVE의_등록_완료_가게는_다시_온보딩하지_않는다() throws Exception {
        Long storeId = draft();
        location(storeId);
        jdbc.update("update store set finalized=true, finalized_at=now(6) where store_id=?", storeId);
        jdbc.update("update owner set status='ACTIVE' where owner_id=?", ownerId);
        mvc.perform(get("/v1/owner/store-registration-progress").cookie(token(ownerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.finalized").value(true))
                .andExpect(jsonPath("$.data.resumeStep").doesNotExist())
                .andExpect(jsonPath("$.data.canFinalize").value(false));
    }

    @Test
    void 인증과_역할을_확인하고_본인_정보만_조회한다() throws Exception {
        Long storeId = draft();
        mvc.perform(get("/v1/owner/store-registration-progress").cookie(token(ownerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.storeId").value(storeId));
        mvc.perform(get("/v1/owner/store-registration-progress")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/owner/store-registration-progress").cookie(new Cookie("accessToken",
                        jwt.createAccessToken(999L, Role.MEMBER, Map.of("issuanceVersion", "0")))))
                .andExpect(status().isForbidden());
        prepareOwner();
        mvc.perform(get("/v1/owner/store-registration-progress").cookie(token(ownerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.storeId").doesNotExist())
                .andExpect(jsonPath("$.data.resumeStep").value("TERMS"));
    }

    private Cookie token(Long id) {
        return new Cookie("accessToken", jwt.createAccessToken(id, Role.OWNER, Map.of("issuanceVersion", "0")));
    }

    private Long ready() {
        Long id = draft();
        progress(id);
        terms();
        location(id);
        hours(id);
        logo(id);
        return id;
    }

    private Long draft() {
        jdbc.update("""
                insert into store(owner_id, name, category_code, representative_name, phone,
                    business_registration_number, business_verification_status, business_verified_at, created_at, updated_at)
                values (?, '가게', 'KOREAN', '대표', '02-123-4567', '1234567890', 'VERIFIED', now(6), now(6), now(6))
                """, ownerId);
        return jdbc.queryForObject("select store_id from store where owner_id=?", Long.class, ownerId);
    }

    private void progress(Long storeId) {
        jdbc.update("""
                insert into store_registration_progress(owner_id, store_id, resume_step, version, created_at, updated_at)
                values (?, ?, 'MENU', 8, now(6), now(6))
                """, ownerId, storeId);
    }

    private void terms() {
        jdbc.update("""
                insert into owner_terms_agreement(owner_id, required, agreed, agreed_at, created_at, updated_at)
                values (?, true, true, now(6), now(6), now(6))
                """, ownerId);
    }

    private void location(Long storeId) {
        jdbc.update("update store set kakao_place_id='place', road_address='도로명 주소', latitude=37.5, longitude=127.1 where store_id=?", storeId);
    }

    private void hours(Long storeId) {
        for (var day : DayOfWeek.values()) {
            jdbc.update("""
                    insert into store_business_hour(store_id, day_of_week, open_time, close_time, closed, created_at, updated_at)
                    values (?, ?, '09:00', '20:00', false, now(6), now(6))
                    """, storeId, day.name());
        }
        jdbc.update("update store_business_hour set closed=true, open_time=null, close_time=null where store_id=? and day_of_week='SUNDAY'", storeId);
    }

    private void logo(Long storeId) {
        jdbc.update("""
                insert into store_image(store_id, image_type, object_key, upload_status, verified_at, created_at, updated_at)
                values (?, 'LOGO', ?, 'VERIFIED', now(6), now(6), now(6))
                """, storeId, UUID.randomUUID().toString());
    }
}
