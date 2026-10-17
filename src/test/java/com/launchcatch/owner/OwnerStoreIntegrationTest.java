package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.owner.entity.Owner;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;


class OwnerStoreIntegrationTest extends AbstractOwnerIntegrationTest {
    private static final String PATH = "/v1/owner/store";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;

    private final List<Long> ownerIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : ownerIds) {
            jdbc.update("delete from store_registration_progress where owner_id = ?", id);
            jdbc.update("delete i from store_menu_image i join store_menu m on m.store_menu_id = i.store_menu_id "
                    + "join store s on s.store_id = m.store_id where s.owner_id = ?", id);
            jdbc.update("delete m from store_menu m join store s on s.store_id = m.store_id where s.owner_id = ?", id);
            jdbc.update("delete i from store_image i join store s on s.store_id = i.store_id where s.owner_id = ?", id);
            jdbc.update("delete h from store_business_hour h join store s on s.store_id = h.store_id where s.owner_id = ?", id);
            jdbc.update("delete from store where owner_id = ?", id);
            jdbc.update("delete from owner_terms_agreement where owner_id = ?", id);
            jdbc.update("delete from owner where owner_id = ?", id);
        }
    }

    private Long owner(String status) {
        var owner = owners.saveAndFlush(Owner.create(UUID.randomUUID() + "@example.com", "hash"));
        ownerIds.add(owner.getId());
        jdbc.update("""
                update owner set status = ?, suspended_at = case when ? = 'SUSPENDED' then now() else null end,
                    withdrawn_at = case when ? = 'WITHDRAWN' then now() else null end where owner_id = ?
                """, status, status, status, owner.getId());
        return owner.getId();
    }

    private Cookie token(Long id) {
        return new Cookie("accessToken", jwt.createAccessToken(id, Role.OWNER, Map.of("issuanceVersion", "0")));
    }

    private Long store(Long ownerId, boolean finalized) {
        jdbc.update("""
                insert into store (owner_id, name, category_code, representative_name, phone,
                    business_registration_number, business_verification_status, business_verified_at,
                    finalized, finalized_at, latitude, longitude, created_at, updated_at)
                values (?, '내 식당', 'KOREAN', '점주', '02-123-4567', '1234567890', 'VERIFIED', now(),
                    ?, case when ? then now() else null end, 37.5, 127.0, now(), now())
                """, ownerId, finalized, finalized);
        return jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
    }

    @Test
    void 가게_생성_전_조회는_어떤_행도_만들지_않는다() throws Exception {
        Long id = owner("ONBOARDING");
        mvc.perform(get(PATH).cookie(token(id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.storeId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.onboarding.resumeStep").value("TERMS"))
                .andExpect(jsonPath("$.data.onboarding.steps.length()").value(6))
                .andExpect(jsonPath("$.data.onboarding.canComplete").value(false));
        agree(id);
        mvc.perform(get(PATH).cookie(token(id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.onboarding.resumeStep").value("STORE_INFO"))
                .andExpect(jsonPath("$.data.onboarding.termsAgreement.required").value(true))
                .andExpect(jsonPath("$.data.onboarding.termsAgreement.termsType").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.onboarding.steps[0].completed").value(true));
        assertThat(jdbc.queryForObject("select count(*) from store where owner_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from store_registration_progress where owner_id = ?",
                Long.class, id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 초안과_등록_완료_가게를_본인_기준으로_조회한다(boolean finalized) throws Exception {
        Long id = owner(finalized ? "ACTIVE" : "ONBOARDING");
        Long storeId = store(id, finalized);
        Long other = owner("ACTIVE");
        Long otherStore = store(other, true);
        jdbc.update("""
                insert into store_registration_progress (owner_id, store_id, resume_step, version, created_at, updated_at)
                values (?, ?, 'LOCATION', 3, now(), now())
                """, id, storeId);
        var before = jdbc.queryForMap("select * from store where store_id = ?", storeId);
        var progressBefore = jdbc.queryForMap("select * from store_registration_progress where owner_id = ?", id);
        mvc.perform(get(PATH).param("ownerId", other.toString()).param("storeId", otherStore.toString())
                        .cookie(token(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.storeId").value(storeId))
                .andExpect(jsonPath("$.data.finalized").value(finalized))
                .andExpect(jsonPath("$.data.name").value("내 식당"))
                .andExpect(jsonPath("$.data.businessRegistrationNumber").value("1234567890"))
                .andExpect(jsonPath("$.data.businessVerificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.kakaoPlaceId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.logoImage").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.businessHours").isEmpty())
                .andExpect(jsonPath("$.data.interiorImages").isEmpty())
                .andExpect(jsonPath("$.data.menus").isEmpty())
                .andExpect(jsonPath("$.data.onboarding.version").value(3))
                .andExpect(jsonPath("$.data.onboarding.canComplete").value(false));
        mvc.perform(get(PATH).cookie(token(id))).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("select * from store where store_id = ?", storeId)).isEqualTo(before);
        assertThat(jdbc.queryForMap("select * from store_registration_progress where owner_id = ?", id))
                .isEqualTo(progressBefore);
    }

    @Test
    void 저장된_시간과_검증된_사진과_선택_메뉴를_정렬해_반환한다() throws Exception {
        Long id = owner("ONBOARDING");
        Long storeId = store(id, false);
        jdbc.update("""
                insert into store_business_hour (store_id, day_of_week, open_time, close_time, closed, created_at, updated_at)
                values (?, 'MONDAY', '09:00', '20:00', false, now(), now())
                """, storeId);
        jdbc.update("""
                insert into store_image (store_id, image_type, object_key, sort_order, upload_status, verified_at, created_at, updated_at)
                values (?, 'LOGO', 'logo.jpg', null, 'VERIFIED', now(), now(), now()),
                    (?, 'INTERIOR', 'second.jpg', 2, 'VERIFIED', now(), now(), now()),
                    (?, 'INTERIOR', 'first.jpg', 1, 'VERIFIED', now(), now(), now()),
                    (?, 'INTERIOR', 'pending.jpg', 3, 'PENDING', null, now(), now())
                """, storeId, storeId, storeId, storeId);
        jdbc.update("""
                insert into store_menu (store_id, name, price, sort_order, created_at, updated_at)
                values (?, '국수', 8000, 1, now(), now())
                """, storeId);
        Long menuId = jdbc.queryForObject("select store_menu_id from store_menu where store_id = ?", Long.class, storeId);
        jdbc.update("""
                insert into store_menu_image (store_menu_id, object_key, upload_status, verified_at, created_at, updated_at)
                values (?, 'menu.jpg', 'VERIFIED', now(), now(), now())
                """, menuId);
        mvc.perform(get(PATH).cookie(token(id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessHours[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.data.businessHours[0].isClosed").value(false))
                .andExpect(jsonPath("$.data.logoImage.objectKey").value("logo.jpg"))
                .andExpect(jsonPath("$.data.logoImage.url").isString())
                .andExpect(jsonPath("$.data.interiorImages.length()").value(2))
                .andExpect(jsonPath("$.data.interiorImages[0].objectKey").value("first.jpg"))
                .andExpect(jsonPath("$.data.menus[0].menuId").value(menuId))
                .andExpect(jsonPath("$.data.menus[0].image.objectKey").value("menu.jpg"))
                .andExpect(jsonPath("$.data.menus[0].price").value(8000));
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void 사용자_역할은_403이다() throws Exception {
        var cookie = new Cookie("accessToken", jwt.createAccessToken(1L, Role.MEMBER));
        mvc.perform(get(PATH).cookie(cookie)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "WITHDRAWN"})
    void 제한된_점주는_403이다(String state) throws Exception {
        mvc.perform(get(PATH).cookie(token(owner(state)))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER-002"));
    }

    private void agree(Long id) {
        // develop 스키마의 구 컬럼에는 테스트용 값만 채운다. 공개 조회는 종류·버전을 반환하지 않는다.
        jdbc.update("""
                insert into owner_terms_agreement (owner_id, terms_type, terms_version, required, agreed, created_at, updated_at)
                values (?, 'LEGACY', 'LEGACY', true, true, now(), now())
                """, id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "VALID", "INCOMPLETE"})
    void 필수_저장값과_선택_메뉴의_유효성으로_최종_등록_가능_여부를_판단한다(String menuState) throws Exception {
        Long id = owner("ONBOARDING");
        Long storeId = store(id, false);
        agree(id);
        jdbc.update("update store set kakao_place_id = '123', road_address = '서울' where store_id = ?", storeId);
        jdbc.update("""
                insert into store_business_hour (store_id, day_of_week, open_time, close_time, closed, created_at, updated_at)
                values (?, 'MONDAY', '09:00', '20:00', false, now(), now())
                """, storeId);
        jdbc.update("""
                insert into store_image (store_id, image_type, object_key, upload_status, verified_at, created_at, updated_at)
                values (?, 'LOGO', ?, 'VERIFIED', now(), now(), now())
                """, storeId, "logo/" + storeId);
        if (!menuState.equals("NONE")) {
            jdbc.update("""
                    insert into store_menu (store_id, name, price, sort_order, created_at, updated_at)
                    values (?, '국수', 8000, 1, now(), now())
                    """, storeId);
        }
        if (menuState.equals("VALID")) {
            Long menuId = jdbc.queryForObject("select store_menu_id from store_menu where store_id = ?", Long.class, storeId);
            jdbc.update("""
                    insert into store_menu_image (store_menu_id, object_key, upload_status, verified_at, created_at, updated_at)
                    values (?, ?, 'VERIFIED', now(), now(), now())
                    """, menuId, "menu/" + storeId);
        }
        mvc.perform(get(PATH).cookie(token(id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarding.canComplete").value(!menuState.equals("INCOMPLETE")))
                .andExpect(jsonPath("$.data.onboarding.resumeStep").value("MENU"))
                .andExpect(jsonPath("$.data.onboarding.steps[5].optional").value(true))
                .andExpect(jsonPath("$.data.onboarding.steps[5].completed").value(menuState.equals("VALID")));
    }
}
