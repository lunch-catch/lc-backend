package com.launchcatch.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class StoreDetailIntegrationTest extends AbstractStoreIntegrationTest {
    private static final AtomicLong OWNER_IDS = new AtomicLong(953000000);
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @Test
    void 필수_정보와_빈_선택_항목을_반환하고_점주_전용_정보를_숨긴다() throws Exception {
        long id = store(true);
        var before = jdbc.queryForMap("select * from store where store_id = ?", id);
        mvc.perform(get("/v1/stores/{storeId}", id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeId").value(id))
                .andExpect(jsonPath("$.data.name").value("상세 식당"))
                .andExpect(jsonPath("$.data.phone").value("02-123-4567"))
                .andExpect(jsonPath("$.data.roadAddress").value("서울 강남구"))
                .andExpect(jsonPath("$.data.latitude").value(37))
                .andExpect(jsonPath("$.data.longitude").value(127))
                .andExpect(jsonPath("$.data.logoImage.url").value("https://cdn.example.com/stores/" + id + "/logo.png"))
                .andExpect(jsonPath("$.data.businessHours.length()").value(7))
                .andExpect(jsonPath("$.data.menus").isEmpty())
                .andExpect(jsonPath("$.data.interiorImages").isEmpty())
                .andExpect(jsonPath("$.data.activeCampaign").value((Object) null))
                .andExpect(jsonPath("$.data.representativeName").doesNotExist())
                .andExpect(jsonPath("$.data.businessRegistrationNumber").doesNotExist())
                .andExpect(jsonPath("$.data.onboarding").doesNotExist());
        assertThat(jdbc.queryForMap("select * from store where store_id = ?", id)).isEqualTo(before);
    }

    @Test
    void 메뉴_이미지가_없어도_대표_이미지와_메뉴를_반환한다() throws Exception {
        long id = store(true);
        long menuId = menu(id, 1);
        jdbc.update("delete from store_menu_image where store_menu_id = ?", menuId);
        mvc.perform(get("/v1/stores/{storeId}", id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logoImage.url").value("https://cdn.example.com/stores/" + id + "/logo.png"))
                .andExpect(jsonPath("$.data.menus[0].menuId").value(menuId))
                .andExpect(jsonPath("$.data.menus[0].name").value("국수"))
                .andExpect(jsonPath("$.data.menus[0].image").value((Object) null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "UPLOADED", "MISSING"})
    void 검증된_대표_이미지가_없으면_상세_조회는_실패한다(String state) throws Exception {
        long id = store(true);
        if (state.equals("MISSING")) {
            jdbc.update("delete from store_image where store_id = ?", id);
        } else {
            jdbc.update("update store_image set upload_status = ?, verified_at = null where store_id = ?", state, id);
        }
        mvc.perform(get("/v1/stores/{storeId}", id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
    }

    @Test
    void 영업시간과_검증된_사진과_메뉴를_표시_순서대로_반환한다() throws Exception {
        long id = store(true);
        image(id, "INTERIOR", 2, "VERIFIED");
        image(id, "INTERIOR", 1, "VERIFIED");
        image(id, "INTERIOR", 3, "PENDING");
        long second = menu(id, 2);
        long first = menu(id, 1);
        mvc.perform(get("/v1/stores/{storeId}", id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessHours[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.data.businessHours[0].breakStartTime").value("15:00:00"))
                .andExpect(jsonPath("$.data.businessHours[0].lastOrderTime").value("19:00:00"))
                .andExpect(jsonPath("$.data.businessHours[6].isClosed").value(true))
                .andExpect(jsonPath("$.data.businessHours[6].openTime").value((Object) null))
                .andExpect(jsonPath("$.data.interiorImages.length()").value(2))
                .andExpect(jsonPath("$.data.interiorImages[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data.menus[0].menuId").value(first))
                .andExpect(jsonPath("$.data.menus[1].menuId").value(second))
                .andExpect(jsonPath("$.data.menus[0].description").value((Object) null))
                .andExpect(jsonPath("$.data.menus[0].image.url").value("https://cdn.example.com/menus/" + first + ".png"));
    }

    @Test
    void 종료된_캠페인도_가게_상세_자체는_조회된다() throws Exception {
        long id = store(true);
        summary(id, "ENDED", LocalDate.now(clock), null);
        mvc.perform(get("/v1/stores/{storeId}", id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeCampaign").value((Object) null));
    }

    @Test
    void 어제와_내일_캠페인을_제외하고_당일_전체_할인을_반환한다() throws Exception {
        long id = store(true);
        summary(id, "ACTIVE", LocalDate.now(clock).minusDays(1), null);
        summary(id, "ACTIVE", LocalDate.now(clock).plusDays(1), null);
        mvc.perform(get("/v1/stores/{storeId}", id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activeCampaign").value((Object) null));
        summary(id, "ACTIVE", LocalDate.now(clock), null);
        mvc.perform(get("/v1/stores/{storeId}", id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeCampaign.campaignId").value(id))
                .andExpect(jsonPath("$.data.activeCampaign.discountTargetType").value("ALL"))
                .andExpect(jsonPath("$.data.activeCampaign.targetMenu").value((Object) null))
                .andExpect(jsonPath("$.data.activeCampaign.usableStartTime").value("12:00:00"));
    }

    @Test
    void 메뉴_할인은_해당_가게의_메뉴명과_가격을_반환한다() throws Exception {
        long id = store(true);
        long menuId = menu(id, 1);
        summary(id, "ACTIVE", LocalDate.now(clock), menuId);
        mvc.perform(get("/v1/stores/{storeId}", id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeCampaign.targetMenu.menuId").value(menuId))
                .andExpect(jsonPath("$.data.activeCampaign.targetMenu.name").value("국수"))
                .andExpect(jsonPath("$.data.activeCampaign.targetMenu.price").value(9000));
    }

    @Test
    void 다른_가게의_메뉴를_잘못_참조한_요약은_정상_응답으로_숨기지_않는다() throws Exception {
        long id = store(true);
        long otherMenu = menu(store(true), 1);
        summary(id, "ACTIVE", LocalDate.now(clock), otherMenu);
        mvc.perform(get("/v1/stores/{storeId}", id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void 초안과_미존재_가게는_같은_404다() throws Exception {
        long draft = store(false);
        mvc.perform(get("/v1/stores/{storeId}", draft).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE-016"));
        mvc.perform(get("/v1/stores/9223372036854775807").with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STORE-016"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "abc"})
    void 잘못된_ID는_400이다(String id) throws Exception {
        mvc.perform(get("/v1/stores/" + id).with(user(new CustomUserDetails(1L, Role.MEMBER))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 미인증_요청은_401이다() throws Exception {
        mvc.perform(get("/v1/stores/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void MEMBER_외_역할은_403이다() throws Exception {
        mvc.perform(get("/v1/stores/1").with(user(new CustomUserDetails(1L, Role.OWNER))))
                .andExpect(status().isForbidden());
    }

    private long store(boolean finalized) {
        long owner = OWNER_IDS.incrementAndGet();
        var now = LocalDateTime.now(clock);
        jdbc.update("""
                insert into store(owner_id, name, category_code, representative_name, business_registration_number,
                    phone, road_address, latitude, longitude, business_verification_status, business_verified_at,
                    finalized, finalized_at, created_at, updated_at)
                values (?, '상세 식당', 'KOREAN', '비공개 대표', '1234567890', '02-123-4567', '서울 강남구',
                    37, 127, 'VERIFIED', ?, ?, ?, ?, ?)
                """, owner, now, finalized, finalized ? now : null, now, now);
        long id = jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, owner);
        image(id, "LOGO", null, "VERIFIED");
        hour(id, "TUESDAY", true);
        hour(id, "WEDNESDAY", true);
        hour(id, "THURSDAY", true);
        hour(id, "FRIDAY", true);
        hour(id, "SATURDAY", true);
        hour(id, "MONDAY", false);
        hour(id, "SUNDAY", true);
        return id;
    }

    private void image(long id, String type, Integer order, String state) {
        var now = LocalDateTime.now(clock);
        String key = "stores/" + id + "/" + (type.equals("LOGO") ? "logo" : "interior" + order) + ".png";
        jdbc.update("""
                insert into store_image(store_id, image_type, object_key, sort_order, upload_status,
                    verified_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, type, key, order, state, state.equals("VERIFIED") ? now : null, now, now);
    }

    private long menu(long id, int order) {
        var now = LocalDateTime.now(clock);
        jdbc.update("""
                insert into store_menu(store_id, name, price, sort_order, created_at, updated_at)
                values (?, '국수', 9000, ?, ?, ?)
                """, id, order, now, now);
        long menu = jdbc.queryForObject("select store_menu_id from store_menu where store_id = ? and sort_order = ?",
                Long.class, id, order);
        jdbc.update("""
                insert into store_menu_image(store_menu_id, object_key, upload_status, verified_at, created_at, updated_at)
                values (?, ?, 'VERIFIED', ?, ?, ?)
                """, menu, "menus/" + menu + ".png", now, now, now);
        return menu;
    }

    private void hour(long id, String day, boolean closed) {
        var now = LocalDateTime.now(clock);
        jdbc.update("""
                insert into store_business_hour(store_id, day_of_week, open_time, close_time, closed,
                    break_start_time, break_end_time, last_order_time, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, day, closed ? null : "11:00", closed ? null : "20:00", closed,
                closed ? null : "15:00", closed ? null : "17:00", closed ? null : "19:00", now, now);
    }

    private void summary(long id, String state, LocalDate date, Long menu) {
        var now = LocalDateTime.now(clock);
        jdbc.update("""
                insert into store_campaign_summary(store_id, campaign_id, business_date, campaign_status,
                    discount_target_type, discount_type, discount_value, target_menu_id,
                    usable_start_time, usable_end_time, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'AMOUNT', 1000, ?, '12:00', '14:00', ?, ?)
                """, id, id, date, state, menu == null ? "ALL" : "MENU", menu, now, now);
    }
}
