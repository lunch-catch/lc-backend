package com.launchcatch.campaign;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.ApiSecurityDefaults;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.SecurityConfig;
import com.launchcatch.auth.exception.AuthExceptionHandler;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.controller.CampaignController;
import com.launchcatch.campaign.dto.CampaignCouponConditionResponse;
import com.launchcatch.campaign.service.CampaignCouponConditionResult;
import com.launchcatch.campaign.service.CampaignCouponConditionService;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import java.time.Clock;
import java.time.ZoneId;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringBootTest(classes = CampaignCouponConditionSecurityIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "app.cors.allowed-origins=https://owner.example.com")
class CampaignCouponConditionSecurityIntegrationTest {

    private static final String SECRET = "test-only-signing-secret-with-at-least-32-bytes";
    private static final String PATH = "/v1/owner/campaigns";
    private static final String VALID_BODY = """
            {
              "storeId": 17,
              "discountTargetType": "ALL",
              "discountType": "PERCENT",
              "discountValue": 15,
              "issueQuantity": 100,
              "usableStartTime": "11:30",
              "usableEndTime": "13:00"
            }
            """;
    private static final String MISSING_DISCOUNT_VALUE_BODY = """
            {
              "storeId": 17,
              "discountTargetType": "ALL",
              "discountType": "PERCENT",
              "issueQuantity": 100,
              "usableStartTime": "11:30",
              "usableEndTime": "13:00"
            }
            """;

    @Autowired private WebApplicationContext context;
    @Autowired private CampaignCouponConditionService service;
    @Autowired private AccessTokenValidAfterRepository cutoff;
    @Autowired private JwtTokenProvider jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, cutoff);
        when(cutoff.isValidAfter(any(), any(), any())).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 가게에_DRAFT가_없으면_201을_반환한다() throws Exception {
        CampaignCouponConditionResponse response = new CampaignCouponConditionResponse(501L, CampaignStatus.DRAFT);
        when(service.handle(eq(7L), any())).thenReturn(new CampaignCouponConditionResult(response, true));

        mvc.perform(post(PATH)
                        .cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.campaignId").value(501))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void 가게에_DRAFT가_있으면_200을_반환한다() throws Exception {
        CampaignCouponConditionResponse response = new CampaignCouponConditionResponse(501L, CampaignStatus.DRAFT);
        when(service.handle(eq(7L), any())).thenReturn(new CampaignCouponConditionResult(response, false));

        mvc.perform(post(PATH)
                        .cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.campaignId").value(501));
    }

    @Test
    void 필수값이_빠지면_400이고_서비스를_부르지_않는다() throws Exception {
        mvc.perform(post(PATH)
                        .cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MISSING_DISCOUNT_VALUE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
        verifyNoInteractions(service);
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(post(PATH)
                        .cookie(new Cookie("accessToken", jwt.createAccessToken(7L, role)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
        verifyNoInteractions(service);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({CampaignSecurityConfig.class, SecurityConfig.class, ApiSecurityDefaults.class,
            CampaignController.class,
            AuthExceptionHandler.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean CampaignCouponConditionService campaignCouponConditionService() {
            return mock(CampaignCouponConditionService.class);
        }
        @Bean AccessTokenValidAfterRepository cutoff() { return mock(AccessTokenValidAfterRepository.class); }
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean JwtTokenProvider jwt() {
            return new JwtTokenProvider(SECRET, 1800000, 86400000, 86400000, 1209600000,
                    Clock.system(ZoneId.of("Asia/Seoul")));
        }
    }
}
