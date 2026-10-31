package com.lunchcatch.store;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class StoreHoursIntegrationTest extends AbstractStoreIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(4890000000L);
    private static final String PATH = "/v1/owner/store/hours";
    @Autowired JdbcTemplate jdbc;
    private long ownerId;
    private long storeId;
    private final List<Long> ownerIds = new ArrayList<>();

    @BeforeEach
    void prepare() {
        ownerId = createOwner();
        storeId = createStore(ownerId);
        var now = LocalDateTime.now();
        jdbc.update("""
                insert into owner_terms_agreement (owner_id, required, agreed, agreed_at, created_at, updated_at)
                values (?, true, true, ?, ?, ?)
                """, ownerId, now, now, now);
        // 위치 저장 API는 선행 PR 범위이므로 저장된 위치와 HOURS 진행 상태를 준비한다.
        jdbc.update("""
                insert into store_registration_progress (owner_id, store_id, resume_step, version, created_at, updated_at)
                values (?, ?, 'HOURS', 3, ?, ?)
                """, ownerId, storeId, now, now);
    }

    private long createOwner() {
        long id = IDS.incrementAndGet();
        ownerIds.add(id);
        var now = LocalDateTime.now();
        jdbc.update("""
                insert into owner (owner_id, email, password_hash, role, status, created_at, updated_at)
                values (?, ?, 'test', 'OWNER', 'ONBOARDING', ?, ?)
                """, id, "hours489-" + id + "@example.com", now, now);
        return id;
    }

    private long createStore(long id) {
        var now = LocalDateTime.now();
        jdbc.update("""
                insert into store (owner_id, name, category_code, representative_name, business_registration_number,
                    phone, business_verification_status, business_verified_at, kakao_place_id, road_address,
                    latitude, longitude, finalized, created_at, updated_at)
                values (?, '시간 식당', 'KOREAN', '점주', '1234567890', '02-123-4567', 'VERIFIED', ?, '123',
                    '서울 도로명 주소', 37.5, 127.0, false, ?, ?)
                """, id, now, now, now);
        return jdbc.queryForObject("select store_id from store where owner_id=?", Long.class, id);
    }

    @AfterEach
    void cleanup() {
        for (Long id : ownerIds) {
            jdbc.update("delete from store_registration_progress where owner_id=?", id);
            jdbc.update("delete h from store_business_hour h join store s on h.store_id=s.store_id where s.owner_id=?", id);
            jdbc.update("delete from store where owner_id=?", id);
            jdbc.update("delete from owner_terms_agreement where owner_id=?", id);
            jdbc.update("delete from owner where owner_id=?", id);
        }
    }

    private String body(Long version, String close) {
        String days = Arrays.stream(DayOfWeek.values()).map(day -> DayOfWeek.SUNDAY.equals(day)
                ? "{\"dayOfWeek\":\"SUNDAY\",\"isClosed\":true}"
                : """
                  {"dayOfWeek":"%s","isClosed":false,"openTime":"09:00:00","closeTime":"%s",
                   "breakStartTime":"14:00:00","breakEndTime":"15:00:00","lastOrderTime":"18:00:00"}
                  """.formatted(day.name(), close)).collect(Collectors.joining(","));
        return "{\"version\":" + version + ",\"businessHours\":[" + days + "]}";
    }

    private MockHttpServletRequestBuilder request(String body) {
        return put(PATH).with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private List<Map<String, Object>> savedHours() {
        return jdbc.queryForList("select * from store_business_hour where store_id=? order by day_of_week", storeId);
    }

    @Test
    void 일주일을_저장하고_조회하며_완료한_단계_재저장은_다른_정보와_재개_단계를_유지한다() throws Exception {
        var storeBefore = jdbc.queryForMap("select * from store where store_id=?", storeId);
        mvc.perform(request(body(3L, "21:00:00"))).andExpect(status().isOk())
                .andExpect(jsonPath("data.storeId").value(storeId))
                .andExpect(jsonPath("data.resumeStep").value("IMAGES")).andExpect(jsonPath("data.version").value(4));
        mvc.perform(get("/v1/owner/store").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.businessHours.length()").value(7))
                .andExpect(jsonPath("data.businessHours[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("data.businessHours[0].openTime").value("09:00:00"))
                .andExpect(jsonPath("data.businessHours[6].dayOfWeek").value("SUNDAY"))
                .andExpect(jsonPath("data.businessHours[6].isClosed").value(true))
                .andExpect(jsonPath("data.onboarding.steps[3].completed").value(true))
                .andExpect(jsonPath("data.onboarding.resumeStep").value("IMAGES"));
        jdbc.update("update store_registration_progress set resume_step='MENU' where owner_id=?", ownerId);
        mvc.perform(request(body(4L, "22:00:00"))).andExpect(status().isOk())
                .andExpect(jsonPath("data.resumeStep").value("MENU")).andExpect(jsonPath("data.version").value(5));
        assertThat(savedHours()).hasSize(7);
        assertThat(jdbc.queryForObject("select count(distinct day_of_week) from store_business_hour where store_id=?", Integer.class, storeId)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select close_time from store_business_hour where store_id=? and day_of_week='MONDAY'", String.class, storeId)).isEqualTo("22:00:00");
        assertThat(jdbc.queryForMap("select * from store where store_id=?", storeId)).isEqualTo(storeBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_LIST", "NULL_LIST", "TOO_MANY", "DUPLICATE_DAY", "NULL_ITEM", "BAD_TIME", "MIDNIGHT_24", "FRACTION", "SHORT_TIME", "OVERNIGHT", "ALL_DAY", "BREAK", "LAST_ORDER", "CLOSED_TIME", "NO_VERSION"})
    void 잘못된_입력은_기존_시간표와_버전을_보존한다(String kind) throws Exception {
        mvc.perform(request(body(3L, "21:00:00"))).andExpect(status().isOk());
        var before = savedHours();
        String input = body(4L, "21:00:00");
        input = switch (kind) {
            case "MISSING_LIST" -> "{\"version\":4}";
            case "NULL_LIST" -> "{\"version\":4,\"businessHours\":null}";
            case "TOO_MANY" -> input.replace("]}", ",{\"dayOfWeek\":\"MONDAY\",\"isClosed\":true}]}");
            case "DUPLICATE_DAY" -> input.replace("SUNDAY", "MONDAY");
            case "NULL_ITEM" -> input.replace("{\"dayOfWeek\":\"SUNDAY\",\"isClosed\":true}", "null");
            case "BAD_TIME" -> input.replace("09:00:00", "25:00:00");
            case "MIDNIGHT_24" -> input.replace("09:00:00", "24:00:00");
            case "FRACTION" -> input.replace("09:00:00", "09:00:00.001");
            case "SHORT_TIME" -> input.replace("09:00:00", "09:00");
            case "OVERNIGHT" -> input.replace("21:00:00", "02:00:00");
            case "ALL_DAY" -> input.replace("21:00:00", "09:00:00");
            case "BREAK" -> input.replace("15:00:00", "13:00:00");
            case "LAST_ORDER" -> input.replace("18:00:00", "14:30:00");
            case "CLOSED_TIME" -> input.replace("\"isClosed\":true", "\"isClosed\":true,\"lastOrderTime\":\"18:00:00\"");
            case "NO_VERSION" -> input.replace("\"version\":4,", "");
            default -> throw new IllegalArgumentException(kind);
        };
        mvc.perform(request(input)).andExpect(status().isBadRequest());
        assertThat(savedHours()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TERMS", "BASIC", "VERIFICATION", "LOCATION", "STEP", "LINK", "NO_PROGRESS"})
    void 선행_조건이나_초안_연결이_맞지_않으면_저장하지_않는다(String kind) throws Exception {
        switch (kind) {
            case "TERMS" -> jdbc.update("delete from owner_terms_agreement where owner_id=?", ownerId);
            case "BASIC" -> jdbc.update("update store set phone=null where store_id=?", storeId);
            case "VERIFICATION" -> jdbc.update("update store set business_verification_status='FAILED',business_verified_at=null where store_id=?", storeId);
            case "LOCATION" -> jdbc.update("update store set road_address=null where store_id=?", storeId);
            case "STEP" -> jdbc.update("update store_registration_progress set resume_step='LOCATION' where owner_id=?", ownerId);
            case "LINK" -> jdbc.update("update store_registration_progress set store_id=? where owner_id=?", createStore(createOwner()), ownerId);
            case "NO_PROGRESS" -> jdbc.update("delete from store_registration_progress where owner_id=?", ownerId);
            default -> throw new IllegalArgumentException(kind);
        }
        int expected = kind.equals("LINK") ? 403 : 409;
        mvc.perform(request(body(3L, "21:00:00"))).andExpect(status().is(expected));
        assertThat(savedHours()).isEmpty();
    }

    @Test
    void 영업일만_보내면_누락한_요일을_휴무로_채우고_선택_시각도_null로_교체한다() throws Exception {
        mvc.perform(request(body(3L, "21:00:00"))).andExpect(status().isOk());
        String input = """
                {"version":4,"businessHours":[
                  {"dayOfWeek":"MONDAY","openTime":"10:00:00","closeTime":"20:00:00"}
                ]}
                """;
        mvc.perform(request(input)).andExpect(status().isOk())
                .andExpect(jsonPath("data.resumeStep").value("IMAGES"))
                .andExpect(jsonPath("data.version").value(5));
        mvc.perform(get("/v1/owner/store").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.businessHours.length()").value(7))
                .andExpect(jsonPath("data.businessHours[0].isClosed").value(false))
                .andExpect(jsonPath("data.businessHours[0].openTime").value("10:00:00"))
                .andExpect(jsonPath("data.onboarding.steps[3].completed").value(true));
        assertThat(jdbc.queryForObject("select count(*) from store_business_hour where store_id=?", Integer.class, storeId)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                select count(*) from store_business_hour where store_id=? and day_of_week <> 'MONDAY'
                  and closed=true and open_time is null and close_time is null
                  and break_start_time is null and break_end_time is null and last_order_time is null
                """, Integer.class, storeId)).isEqualTo(6);
        var monday = jdbc.queryForMap("select * from store_business_hour where store_id=? and day_of_week='MONDAY'", storeId);
        assertThat(monday.get("break_start_time")).isNull();
        assertThat(monday.get("break_end_time")).isNull();
        assertThat(monday.get("last_order_time")).isNull();
    }

    @Test
    void 빈_배열은_기존_영업일을_모두_휴무로_덮어쓴다() throws Exception {
        mvc.perform(request(body(3L, "21:00:00"))).andExpect(status().isOk());
        mvc.perform(request("{\"version\":4,\"businessHours\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("data.version").value(5));
        assertThat(jdbc.queryForObject("""
                select count(*) from store_business_hour where store_id=? and closed=true
                  and open_time is null and close_time is null and break_start_time is null
                  and break_end_time is null and last_order_time is null
                """, Integer.class, storeId)).isEqualTo(7);
    }

    @Test
    void 진행_상태_변경_실패는_기존_시간표_삭제와_새_저장도_롤백한다() throws Exception {
        mvc.perform(request(body(3L, "21:00:00"))).andExpect(status().isOk());
        var before = savedHours();
        jdbc.update("update store_registration_progress set version=? where owner_id=?", Long.MAX_VALUE, ownerId);
        mvc.perform(request(body(Long.MAX_VALUE, "22:00:00"))).andExpect(status().isInternalServerError());
        assertThat(savedHours()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 같은_버전의_동시_저장은_한_번만_성공한다() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> save = () -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(request(body(3L, "21:00:00"))).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(save);
            var second = executor.submit(save);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(savedHours()).hasSize(7);
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ACTIVE는_진행_상태의_존재와_무관하게_시간표만_수정한다(boolean hasProgress) throws Exception {
        jdbc.update("update owner set status='ACTIVE' where owner_id=?", ownerId);
        jdbc.update("update store set finalized=true,finalized_at=? where store_id=?", LocalDateTime.now(), storeId);
        if (!hasProgress) jdbc.update("delete from store_registration_progress where owner_id=?", ownerId);
        var before = jdbc.queryForList("select * from store_registration_progress where owner_id=?", ownerId);
        mvc.perform(request(body(null, "21:00:00"))).andExpect(status().isOk())
                .andExpect(jsonPath("data.storeId").value(storeId))
                .andExpect(jsonPath("data.version").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("data.resumeStep").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(request(body(null, "22:00:00"))).andExpect(status().isOk());
        assertThat(savedHours()).hasSize(7);
        assertThat(jdbc.queryForList("select * from store_registration_progress where owner_id=?", ownerId)).isEqualTo(before);
        mvc.perform(request(body(3L, "21:00:00"))).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/owner/store").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.businessHours[0].closeTime").value("22:00:00"));
    }

    @Test
    void develop의_위치_저장과_영업시간_저장을_온보딩_조회로_이어간다() throws Exception {
        jdbc.update("delete from store_registration_progress where owner_id=?", ownerId);
        jdbc.update("delete from owner_terms_agreement where owner_id=?", ownerId);
        jdbc.update("delete from store where store_id=?", storeId);
        mvc.perform(put("/v1/owner/terms-agreements").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk());
        mvc.perform(put("/v1/owner/store/basic").with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"version":1,"name":"시간 식당","categoryCode":"KOREAN","representativeName":"점주",
                                 "businessRegistrationNumber":"1234567890","phone":"02-123-4567"}
                                """))
                .andExpect(status().isOk());
        storeId = jdbc.queryForObject("select store_id from store where owner_id=?", Long.class, ownerId);
        mvc.perform(put("/v1/owner/store/location").with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"version":2,"kakaoPlaceId":"123","roadAddress":"서울 도로명 주소",
                                 "latitude":37.5,"longitude":127.0}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("data.resumeStep").value("HOURS"));
        mvc.perform(get("/v1/owner/onboarding").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.resumeStep").value("HOURS"))
                .andExpect(jsonPath("data.steps[2].completed").value(true))
                .andExpect(jsonPath("data.steps[3].completed").value(false));
        mvc.perform(request("""
                {"version":3,"businessHours":[
                  {"dayOfWeek":"MONDAY","openTime":"09:00:00","closeTime":"21:00:00"}
                ]}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("data.resumeStep").value("IMAGES"));
        mvc.perform(get("/v1/owner/onboarding").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.storeId").value(storeId))
                .andExpect(jsonPath("data.resumeStep").value("IMAGES"))
                .andExpect(jsonPath("data.version").value(4))
                .andExpect(jsonPath("data.steps[3].completed").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN_TIME", "BREAK_TIME", "CLOSED_TIME"})
    void 두_조회는_7행이_있어도_유효하지_않은_시간표를_HOURS_미완료로_판단한다(String kind) throws Exception {
        mvc.perform(request(body(3L, "21:00:00"))).andExpect(status().isOk());
        switch (kind) {
            case "OPEN_TIME" -> jdbc.update("update store_business_hour set open_time=close_time where store_id=? and day_of_week='MONDAY'", storeId);
            case "BREAK_TIME" -> jdbc.update("update store_business_hour set break_start_time=break_end_time where store_id=? and day_of_week='MONDAY'", storeId);
            case "CLOSED_TIME" -> jdbc.update("update store_business_hour set last_order_time='18:00:00' where store_id=? and day_of_week='SUNDAY'", storeId);
            default -> throw new IllegalArgumentException(kind);
        }
        mvc.perform(get("/v1/owner/onboarding").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.resumeStep").value("HOURS"))
                .andExpect(jsonPath("data.steps[3].completed").value(false))
                .andExpect(jsonPath("data.canFinalize").value(false));
        mvc.perform(get("/v1/owner/store").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.onboarding.steps[3].completed").value(false))
                .andExpect(jsonPath("data.onboarding.canComplete").value(false));
    }

    @Test
    void 일부_요일만_저장된_시간표는_조회에서_미완료다() throws Exception {
        jdbc.update("""
                insert into store_business_hour (store_id,day_of_week,closed,created_at,updated_at)
                values (?,'MONDAY',true,?,?)
                """, storeId, LocalDateTime.now(), LocalDateTime.now());
        mvc.perform(get("/v1/owner/store").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.onboarding.steps[3].hasSavedData").value(true))
                .andExpect(jsonPath("data.onboarding.steps[3].completed").value(false))
                .andExpect(jsonPath("data.onboarding.resumeStep").value("HOURS"))
                .andExpect(jsonPath("data.onboarding.canComplete").value(false));
    }

    @Test
    void 인증과_역할과_DB_상태와_최종_등록_여부를_검증한다() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(3L,"21:00:00")))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).with(user(new CustomUserDetails(ownerId, Role.MEMBER)))
                .contentType(MediaType.APPLICATION_JSON).content(body(3L,"21:00:00")))
                .andExpect(status().isForbidden());
        jdbc.update("update owner set status='SUSPENDED',suspended_at=? where owner_id=?", LocalDateTime.now(), ownerId);
        mvc.perform(request(body(3L,"21:00:00"))).andExpect(status().isForbidden());
        jdbc.update("update owner set status='WITHDRAWN',suspended_at=null,withdrawn_at=? where owner_id=?", LocalDateTime.now(), ownerId);
        mvc.perform(request(body(3L,"21:00:00"))).andExpect(status().isForbidden());
        jdbc.update("update owner set status='ACTIVE',withdrawn_at=null where owner_id=?", ownerId);
        mvc.perform(request(body(null,"21:00:00"))).andExpect(status().isForbidden());
        assertThat(savedHours()).isEmpty();
    }
}
