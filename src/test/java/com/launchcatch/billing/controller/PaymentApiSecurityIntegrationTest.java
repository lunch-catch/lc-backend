package com.launchcatch.billing.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.launchcatch.admin.AdminAccessTokenRevocation;
import com.launchcatch.admin.repository.AdminAccessTokenRevocationRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.billing.client.FakePaymentGateway;
import com.launchcatch.billing.client.PgUnknownException;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * 충전 경로 다섯 개의 인증과 역할 검사를 실제 보안 체인으로 확인한다.
 * 토큰이 없으면 401(AUTH-005), 점주가 아니면 403(AUTH-006) 이고 점주는 통과한다.
 * 승인 확정의 콜론 경로(/{paymentId}:confirm)가 실제로 연결되는지, 200 과 202 가 상태에 따라 갈리는지,
 * 다른 점주의 결제가 404 로 가려지는지도 함께 본다. 결제대행사만 가짜로 바꾼다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiSecurityIntegrationTest {

    private static final String POLICY = "/v1/owner/point-policy";
    private static final String PAYMENTS = "/v1/owner/payments";
    private static final String PAYMENT = "/v1/owner/payments/1";
    private static final String CONFIRM = "/v1/owner/payments/1:confirm";

    private static final String ORDER_BODY = "{\"amount\": 30000}";

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

    @TestConfiguration
    static class GatewayConfig {

        @Bean
        @Primary
        FakePaymentGateway fakePaymentGateway() {
            return new FakePaymentGateway();
        }
    }

    @MockitoBean
    private AdminAccessTokenRevocationRepository adminRevocations;

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired FakePaymentGateway gateway;
    @Autowired JdbcTemplate jdbc;

    private Cookie token(Long id, Role role) {
        return new Cookie("accessToken", jwtTokenProvider.createAccessToken(id, role));
    }

    @BeforeEach
    void resetGateway() {
        org.mockito.Mockito.when(adminRevocations.findRevocation(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new AdminAccessTokenRevocation(0L, null)));
        gateway.failConfirmWith(null).failInquiryWith(null);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from point_ledger");
        jdbc.update("delete from payment_reconciliation_outbox");
        jdbc.update("delete from payment");
        jdbc.update("delete from point_account");
    }

    private String orderAs(Long ownerId) throws Exception {
        return mockMvc.perform(post(PAYMENTS).cookie(token(ownerId, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static String confirmBody(String orderId, String pgTid) {
        return "{\"orderId\": \"" + orderId + "\", \"pgTid\": \"" + pgTid + "\"}";
    }

    @ParameterizedTest
    @ValueSource(strings = {POLICY, PAYMENTS, PAYMENT})
    void 토큰이_없으면_조회_경로는_401_AUTH_005다(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @ValueSource(strings = {PAYMENTS, CONFIRM})
    void 토큰이_없으면_쓰기_경로는_401_AUTH_005다(String path) throws Exception {
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "MEMBER"})
    void 점주가_아니면_충전_경로를_부를_수_없다(String role) throws Exception {
        Cookie cookie = token(1L, Role.valueOf(role));

        for (String path : new String[] {POLICY, PAYMENTS, PAYMENT}) {
            mockMvc.perform(get(path).cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"));
        }
        mockMvc.perform(post(PAYMENTS).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
        mockMvc.perform(post(CONFIRM).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("ord_x", "tgen_x")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void 점주는_오늘_정책을_조회한다() throws Exception {
        mockMvc.perform(get(POLICY).cookie(token(1L, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minChargeAmount").value(10000))
                .andExpect(jsonPath("$.data.chargeProducts.length()").value(4));
    }

    @Test
    void 충전_상품에_없는_금액은_400_BILLING_002다() throws Exception {
        mockMvc.perform(post(PAYMENTS).cookie(token(1L, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 20000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BILLING-002"));
    }

    @Test
    void 금액이_없으면_400_COMMON_002다() throws Exception {
        mockMvc.perform(post(PAYMENTS).cookie(token(1L, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    void 주문하고_승인을_확정하면_200이고_결제_상태와_내역에_승인으로_보인다() throws Exception {
        String order = orderAs(1L);
        Integer paymentId = JsonPath.read(order, "$.data.paymentId");
        String orderId = JsonPath.read(order, "$.data.orderId");

        mockMvc.perform(post(PAYMENTS + "/" + paymentId + ":confirm").cookie(token(1L, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody(orderId, "tgen_api_1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.chargedPoint").value(30000));

        mockMvc.perform(get(PAYMENTS + "/" + paymentId).cookie(token(1L, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayStatus").value("COMPLETED"));
        mockMvc.perform(get(PAYMENTS).cookie(token(1L, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].paymentId").value(paymentId));
    }

    @Test
    void 결과를_정하지_못하면_202이고_새_주문은_409_BILLING_008이다() throws Exception {
        String order = orderAs(1L);
        Integer paymentId = JsonPath.read(order, "$.data.paymentId");
        String orderId = JsonPath.read(order, "$.data.orderId");
        gateway.failConfirmWith(new PgUnknownException("read timeout", new RuntimeException()));

        mockMvc.perform(post(PAYMENTS + "/" + paymentId + ":confirm").cookie(token(1L, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody(orderId, "tgen_api_2")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.displayStatus").value("CONFIRMING"));

        mockMvc.perform(post(PAYMENTS).cookie(token(1L, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING-008"));
    }

    @Test
    void 주문_번호가_다르면_409_BILLING_017이다() throws Exception {
        Integer paymentId = JsonPath.read(orderAs(1L), "$.data.paymentId");

        mockMvc.perform(post(PAYMENTS + "/" + paymentId + ":confirm").cookie(token(1L, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody("ord_forged", "tgen_api_3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING-017"));
    }

    @Test
    void 다른_점주의_결제는_조회도_확정도_404_BILLING_004다() throws Exception {
        String order = orderAs(1L);
        Integer paymentId = JsonPath.read(order, "$.data.paymentId");
        String orderId = JsonPath.read(order, "$.data.orderId");

        mockMvc.perform(get(PAYMENTS + "/" + paymentId).cookie(token(2L, Role.OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING-004"));
        mockMvc.perform(post(PAYMENTS + "/" + paymentId + ":confirm").cookie(token(2L, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody(orderId, "tgen_api_4")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BILLING-004"));
    }

    @Test
    void 결제_내역에는_다른_점주의_결제가_섞이지_않는다() throws Exception {
        String order = orderAs(1L);
        Integer paymentId = JsonPath.read(order, "$.data.paymentId");
        String orderId = JsonPath.read(order, "$.data.orderId");
        mockMvc.perform(post(PAYMENTS + "/" + paymentId + ":confirm").cookie(token(1L, Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody(orderId, "tgen_api_5")))
                .andExpect(status().isOk());

        mockMvc.perform(get(PAYMENTS).cookie(token(2L, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 승인을_시도하지_않은_주문은_결제_내역에_나오지_않는다() throws Exception {
        orderAs(1L);

        mockMvc.perform(get(PAYMENTS).cookie(token(1L, Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }
}
