package com.lunchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/*
 * 충전 상한이 실제 Valkey 카운터와 MVC 의 인터셉터 순서로 걸리는지 본다(#1122).
 *
 * 점주 행을 넣지 않는다. 상한 안의 요청은 뒤의 점주 상태 검사에서 403 이 되고, 넘친 요청은 그 검사 전에 429 가 된다.
 * 429 와 403 이 갈리는 것으로 상한이 DB 를 읽는 검사보다 앞에 있다는 것을 함께 본다.
 */
class ChargeRateLimitIntegrationTest extends AbstractBillingIntegrationTest {

    private static final long OWNER = 91_220_001L;
    private static final long OTHER_OWNER = 91_220_002L;

    @Autowired private WebApplicationContext webContext;
    @Autowired private StringRedisTemplate redisTemplate;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        redisTemplate.delete(List.of(
                "billing:chargeRateLimit:" + OWNER, "billing:chargeRateLimit:" + OTHER_OWNER));
    }

    @Test
    void 주문과_승인_확정을_합쳐_네번째부터_429_와_Retry_After_를_돌려준다() throws Exception {
        assertThat(perform(order(OWNER)).getStatus()).isEqualTo(403);
        assertThat(perform(confirm(OWNER)).getStatus()).isEqualTo(403);
        assertThat(perform(order(OWNER)).getStatus()).isEqualTo(403);

        MockHttpServletResponse rejected = perform(confirm(OWNER));

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentAsString()).contains("BILLING-024");
        assertThat(Long.parseLong(rejected.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 10L);
    }

    @Test
    void 한_점주가_상한을_넘어도_다른_점주는_통과한다() throws Exception {
        for (int i = 0; i < 4; i++) {
            perform(order(OWNER));
        }

        assertThat(perform(order(OWNER)).getStatus()).isEqualTo(429);
        assertThat(perform(order(OTHER_OWNER)).getStatus()).isEqualTo(403);
    }

    @Test
    void 결제_내역_조회는_세지_않는다() throws Exception {
        for (int i = 0; i < 4; i++) {
            perform(get("/v1/owner/payments").with(user(new CustomUserDetails(OWNER, Role.OWNER))));
        }

        assertThat(perform(order(OWNER)).getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletRequestBuilder order(long ownerId) {
        return post("/v1/owner/payments")
                .with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10000}");
    }

    private static MockHttpServletRequestBuilder confirm(long ownerId) {
        return post("/v1/owner/payments/{paymentId}:confirm", 1L)
                .with(user(new CustomUserDetails(ownerId, Role.OWNER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"ord_1\",\"pgTid\":\"tgen_1\"}");
    }
}
