package com.lunchcatch.store;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class StoreInfoIntegrationTest extends AbstractStoreIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(4150000000L);
    private static final String PATH = "/v1/owner/store/basic";
    @Autowired JdbcTemplate jdbc;
    private long ownerId;

    @BeforeEach
    void prepare() {
        ownerId = IDS.incrementAndGet();
        var now = LocalDateTime.now();
        jdbc.update("insert into owner (owner_id,email,password_hash,role,status,created_at,updated_at) values (?,?,'test','OWNER','ONBOARDING',?,?)",
                ownerId, "store415-" + ownerId + "@example.com", now, now);
        jdbc.update("insert into store_registration_progress (owner_id,resume_step,version,created_at,updated_at) values (?,'STORE_INFO',1,?,?)", ownerId, now, now);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from owner_terms_agreement where owner_id=?", ownerId);
        jdbc.update("delete from store_registration_progress where owner_id=?", ownerId);
        jdbc.update("delete from store where owner_id=?", ownerId);
        jdbc.update("delete from owner where owner_id=?", ownerId);
    }

    private String body(long version, String number, String name) {
        return """
                {"version":%d,"name":"%s","categoryCode":"KOREAN","representativeName":"대표자",
                 "businessRegistrationNumber":"%s","phone":"02-123-4567"}
                """.formatted(version, name, number);
    }

    private MockHttpServletRequestBuilder request(String body) {
        return put(PATH).with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void 약관_동의_후_최초_등록과_재저장은_같은_초안을_수정하고_다른_단계_정보를_보존한다() throws Exception {
        jdbc.update("delete from store_registration_progress where owner_id=?", ownerId);
        mvc.perform(put("/v1/owner/terms-agreements")
                        .with(user(new CustomUserDetails(ownerId, Role.OWNER))))
                .andExpect(status().isOk()).andExpect(jsonPath("data.resumeStep").value("STORE_INFO"))
                .andExpect(jsonPath("data.version").value(1));
        mvc.perform(request(body(1, "1234567890", "처음")))
                .andExpect(status().isOk()).andExpect(jsonPath("data.resumeStep").value("LOCATION"))
                .andExpect(jsonPath("data.version").value(2)).andExpect(jsonPath("data.verificationStatus").value("VERIFIED"));
        Long storeId = jdbc.queryForObject("select store_id from store where owner_id=?", Long.class, ownerId);
        assertThat(jdbc.queryForObject("select finalized from store where store_id=?", Boolean.class, storeId)).isFalse();
        assertThat(jdbc.queryForObject("select status from owner where owner_id=?", String.class, ownerId)).isEqualTo("ONBOARDING");
        jdbc.update("update store set road_address='보존 주소',latitude=37.5,longitude=127.0,kakao_place_id='415' where store_id=?", storeId);
        jdbc.update("update store_registration_progress set resume_step='HOURS' where owner_id=?", ownerId);
        mvc.perform(request(body(2, "9876543210", "수정"))).andExpect(status().isOk())
                .andExpect(jsonPath("data.storeId").value(storeId)).andExpect(jsonPath("data.resumeStep").value("HOURS"))
                .andExpect(jsonPath("data.version").value(3));
        assertThat(jdbc.queryForObject("select count(*) from store where owner_id=?", Integer.class, ownerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from store where store_id=?", String.class, storeId)).isEqualTo("수정");
        assertThat(jdbc.queryForObject("select road_address from store where store_id=?", String.class, storeId)).isEqualTo("보존 주소");
        assertThat(jdbc.queryForObject("select business_verification_status from store where store_id=?", String.class, storeId)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select business_verified_at from store where store_id=?", LocalDateTime.class, storeId)).isNotNull();
    }

    @Test
    void Mock_실패는_FAILED를_응답하고_가게를_생성하지_않는다() throws Exception {
        mvc.perform(request(body(1, "0000000000", "실패"))).andExpect(status().isOk())
                .andExpect(jsonPath("data.storeId").doesNotExist()).andExpect(jsonPath("data.version").value(1))
                .andExpect(jsonPath("data.resumeStep").value("STORE_INFO"))
                .andExpect(jsonPath("data.verificationStatus").value("FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from store where owner_id=?", Integer.class, ownerId)).isZero();
    }

    @Test
    void 인증과_역할과_DB_상태를_검증한다() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(1,"1234567890","가게")))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).with(user(new CustomUserDetails(ownerId, Role.MEMBER)))
                .contentType(MediaType.APPLICATION_JSON).content(body(1,"1234567890","가게")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("AUTH-006"));
        jdbc.update("update owner set status='ACTIVE' where owner_id=?", ownerId);
        mvc.perform(request(body(1,"1234567890","가게"))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from store where owner_id=?", Integer.class, ownerId)).isZero();
    }

    @Test
    void 요청_버전이_없으면_400으로_거부하고_저장하지_않는다() throws Exception {
        var input = body(1, "1234567890", "가게").replace("\"version\":1,", "");
        mvc.perform(request(input)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from store where owner_id=?", Integer.class, ownerId)).isZero();
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(1);
    }

    @Test
    void 동시_저장은_하나만_성공하며_가게가_중복_생성되지_않는다() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> save = () -> {
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(request(body(1,"1234567890","가게"))).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(save);
            var second = executor.submit(save);
            start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("select count(*) from store where owner_id=?", Integer.class, ownerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(2);
    }

    @Test
    void 가게_INSERT_후_진행_상태_갱신이_실패하면_초안도_롤백한다() throws Exception {
        // 진행 상태 갱신에서 예외를 발생시켜 이미 생성한 초안까지 롤백되는지 확인한다.
        jdbc.update("update store_registration_progress set version=? where owner_id=?", Long.MAX_VALUE, ownerId);
        mvc.perform(request(body(Long.MAX_VALUE,"1234567890","가게"))).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("select count(*) from store where owner_id=?", Integer.class, ownerId)).isZero();
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id=?", Long.class, ownerId)).isEqualTo(Long.MAX_VALUE);
    }
}
