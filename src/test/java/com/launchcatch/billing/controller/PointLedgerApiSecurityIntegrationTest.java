package com.launchcatch.billing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * 원장 조회 다섯 경로의 인증과 역할 검사를 실제 보안 체인으로 확인한다.
 * 토큰이 없으면 401(AUTH-005), 역할이 맞지 않으면 403(AUTH-006) 이고 맞는 역할은 200 이다.
 * 점주 경로는 토큰의 점주 ID 로만 읽으므로 다른 점주의 원장이 섞이지 않는지도 함께 본다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PointLedgerApiSecurityIntegrationTest {

    private static final String BALANCE = "/v1/owner/points/balance";
    private static final String LEDGER = "/v1/owner/points/ledger";
    private static final String SPEND_SUMMARY = "/v1/owner/points/spend-summary?from=2026-10-01&to=2026-10-01";
    private static final String ADMIN_LEDGER = "/v1/admin/point-ledger?ownerId=1&from=2026-10-01&to=2026-10-01";
    private static final String ADMIN_SUMMARY =
            "/v1/admin/point-ledger/summary?ownerId=1&period=DAILY&from=2026-10-01&to=2026-10-01";

    private static final String DUMMY_JWT_SECRET =
            "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;

    private Cookie token(Long id, Role role) {
        return new Cookie("accessToken", jwtTokenProvider.createAccessToken(id, role));
    }

    @ParameterizedTest
    @ValueSource(strings = {BALANCE, LEDGER, SPEND_SUMMARY, ADMIN_LEDGER, ADMIN_SUMMARY})
    void 토큰이_없으면_401_AUTH_005다(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @ValueSource(strings = {BALANCE, LEDGER, SPEND_SUMMARY})
    void 관리자_토큰으로는_점주_경로를_부를_수_없다(String path) throws Exception {
        mockMvc.perform(get(path).cookie(token(1L, Role.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @ParameterizedTest
    @ValueSource(strings = {BALANCE, LEDGER, SPEND_SUMMARY})
    void 회원_토큰으로는_점주_경로를_부를_수_없다(String path) throws Exception {
        mockMvc.perform(get(path).cookie(token(1L, Role.MEMBER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_LEDGER, ADMIN_SUMMARY})
    void 점주_토큰으로는_관리자_경로를_부를_수_없다(String path) throws Exception {
        mockMvc.perform(get(path).cookie(token(1L, Role.OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @ParameterizedTest
    @ValueSource(strings = {BALANCE, LEDGER, SPEND_SUMMARY})
    void 점주_토큰으로_점주_경로를_부르면_200이다(String path) throws Exception {
        mockMvc.perform(get(path).cookie(token(1L, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_LEDGER, ADMIN_SUMMARY})
    void 관리자와_최고_관리자_토큰으로_관리자_경로를_부르면_200이다(String path) throws Exception {
        mockMvc.perform(get(path).cookie(token(1L, Role.ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(get(path).cookie(token(2L, Role.SUPER_ADMIN)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/admin/point-ledger?from=2026-10-01&to=2026-10-01",
            "/v1/admin/point-ledger/summary?period=DAILY&from=2026-10-01&to=2026-10-01"
    })
    void 관리자_경로에서_점주_ID가_빠지면_400_COMMON_002다(String path) throws Exception {
        mockMvc.perform(get(path).cookie(token(1L, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    void 점주는_자기_원장만_읽는다() throws Exception {
        jdbc.update("""
                insert into payment (owner_id, order_id, pg_tid, amount, status, approved_at, created_at, updated_at)
                values (2, 'order-sec-2', 'tgen-sec-2', 30000, 'APPROVED',
                        '2026-10-01 10:00:00', '2026-10-01 09:59:00', '2026-10-01 10:00:00')
                """);
        Long paymentId = jdbc.queryForObject(
                "select payment_id from payment where order_id = 'order-sec-2'", Long.class);
        jdbc.update("""
                insert into point_ledger (owner_id, payment_id, entry_type, source, amount, created_at, updated_at)
                values (2, ?, 'CHARGE', 'REALTIME', 30000, '2026-10-01 10:00:00', '2026-10-01 10:00:00')
                """, paymentId);

        mockMvc.perform(get(BALANCE).cookie(token(1L, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0));
        mockMvc.perform(get(LEDGER).cookie(token(1L, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mockMvc.perform(get(BALANCE).cookie(token(2L, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(30000));
    }
}
