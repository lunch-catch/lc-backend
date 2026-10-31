package com.lunchcatch.store;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class StoreLocationIntegrationTest extends AbstractStoreIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(4820000000L);
    private static final String PATH = "/v1/owner/store/location";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private long ownerId;
    private long storeId;

    @BeforeEach
    void prepare() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ownerId = IDS.incrementAndGet();
        var now = LocalDateTime.now();
        jdbc.update("insert into owner (owner_id,email,password_hash,role,status,created_at,updated_at) values (?,?,'test','OWNER','ONBOARDING',?,?)",
                ownerId, "location482-" + ownerId + "@example.com", now, now);
        mvc.perform(put("/v1/owner/terms-agreements").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk());
        mvc.perform(put("/v1/owner/store/basic").with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"version":1,"name":"가게","categoryCode":"KOREAN","representativeName":"대표",
                    "businessRegistrationNumber":"1234567890","phone":"02-123-4567"}
                    """)).andExpect(status().isOk());
        storeId = jdbc.queryForObject("select store_id from store where owner_id=?", Long.class, ownerId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from store_business_hour where store_id=?", storeId);
        jdbc.update("delete from owner_terms_agreement where owner_id=?", ownerId);
        jdbc.update("delete from store_registration_progress where owner_id=?", ownerId);
        jdbc.update("delete from store where owner_id=?", ownerId);
        jdbc.update("delete from owner where owner_id=?", ownerId);
    }

    private String body(long version, String placeId) {
        return """
                {"version":%d,"kakaoPlaceId":"%s","roadAddress":"서울 강남구 테헤란로 123",
                "latitude":37.5000001,"longitude":127.0000001}
                """.formatted(version, placeId);
    }

    private MockHttpServletRequestBuilder request(String body) {
        return put(PATH).with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertVersion(long version) {
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(version);
    }

    @Test
    void 확정_위치_저장과_재저장이_조회와_진행_상태에_반영된다() throws Exception {
        mvc.perform(request(body(2, "123"))).andExpect(status().isOk())
                .andExpect(jsonPath("data.storeId").value(storeId))
                .andExpect(jsonPath("data.resumeStep").value("HOURS")).andExpect(jsonPath("data.version").value(3));
        mvc.perform(get("/v1/owner/store").with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.kakaoPlaceId").value("123"))
                .andExpect(jsonPath("data.roadAddress").value("서울 강남구 테헤란로 123"))
                .andExpect(jsonPath("data.latitude").value(37.5000001)).andExpect(jsonPath("data.longitude").value(127.0000001))
                .andExpect(jsonPath("data.onboarding.resumeStep").value("HOURS"))
                .andExpect(jsonPath("data.onboarding.steps[2].completed").value(true));
        var now = LocalDateTime.now();
        jdbc.update("insert into store_business_hour (store_id,day_of_week,closed,created_at,updated_at) values (?,'MONDAY',1,?,?)", storeId, now, now);
        jdbc.update("update store_registration_progress set resume_step='IMAGES' where owner_id=?", ownerId);
        mvc.perform(request(body(3, "456"))).andExpect(status().isOk())
                .andExpect(jsonPath("data.resumeStep").value("IMAGES")).andExpect(jsonPath("data.version").value(4));
        assertThat(jdbc.queryForObject("select kakao_place_id from store where store_id=?", String.class, storeId)).isEqualTo("456");
        assertThat(jdbc.queryForObject("select count(*) from store_business_hour where store_id=?", Integer.class, storeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from store where store_id=?", String.class, storeId)).isEqualTo("가게");
        assertThat(jdbc.queryForObject("select status from owner where owner_id=?", String.class, ownerId)).isEqualTo("ONBOARDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"version\": 2, \"kakaoPlaceId\": \" \", \"roadAddress\": \"주소\", \"latitude\": 37, \"longitude\": 127}",
            "{\"version\":2,\"kakaoPlaceId\":\"123\",\"roadAddress\":\"주소\",\"latitude\":91,\"longitude\":127}",
            "{\"version\":2,\"kakaoPlaceId\":\"123\",\"roadAddress\":\"주소\",\"latitude\":37,\"longitude\":181}",
            "{\"version\":2,\"kakaoPlaceId\":\"123\",\"roadAddress\":\"주소\",\"latitude\":null,\"longitude\":127}"
    })
    void 잘못된_입력은_위치와_진행_상태를_보존한다(String input) throws Exception {
        mvc.perform(request(input)).andExpect(status().isBadRequest());
        assertVersion(2);
        assertThat(jdbc.queryForObject("select kakao_place_id from store where store_id=?", String.class, storeId)).isNull();
    }

    @Test
    void 인증과_역할을_확인한다() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(2,"123")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTH-005"));
        mvc.perform(put(PATH).with(user(new CustomUserDetails(ownerId, Role.MEMBER)))
                .contentType(MediaType.APPLICATION_JSON).content(body(2,"123")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("AUTH-006"));
        assertVersion(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "SUSPENDED", "WITHDRAWN"})
    void 입점_신청_외_상태는_저장하지_않는다(String status) throws Exception {
        jdbc.update("update owner set status=?,suspended_at=?,withdrawn_at=? where owner_id=?", status, LocalDateTime.now(), LocalDateTime.now(), ownerId);
        mvc.perform(request(body(2,"123"))).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("OWNER-002"));
        assertVersion(2);
    }

    @Test
    void 약관_누락은_진행_단계만_믿고_저장하지_않는다() throws Exception {
        jdbc.update("delete from owner_terms_agreement where owner_id=?", ownerId);
        mvc.perform(request(body(2,"123"))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("STORE-002"));
        assertVersion(2);
    }

    @Test
    void 오래된_버전과_연결_불일치를_거부한다() throws Exception {
        mvc.perform(request(body(1,"123"))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("STORE-001"));
        jdbc.update("update store set owner_id=? where store_id=?", ownerId + 1000000, storeId);
        try {
            mvc.perform(request(body(2,"123"))).andExpect(status().isForbidden()).andExpect(jsonPath("code").value("STORE-004"));
        } finally {
            jdbc.update("update store set owner_id=? where store_id=?", ownerId, storeId);
        }
        assertVersion(2);
    }

    @Test
    void 진행_상태_갱신_실패는_이미_저장한_위치도_롤백한다() throws Exception {
        jdbc.update("update store_registration_progress set version=? where owner_id=?", Long.MAX_VALUE, ownerId);
        mvc.perform(request(body(Long.MAX_VALUE,"123"))).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("select kakao_place_id from store where store_id=?", String.class, storeId)).isNull();
        assertVersion(Long.MAX_VALUE);
    }

    @Test
    void 동일_버전의_동시_저장은_한_요청만_반영한다() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> save = () -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(request(body(2,"123"))).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(save);
            var second = executor.submit(save);
            start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertVersion(3);
    }
}
