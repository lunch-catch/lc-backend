package com.lunchcatch.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.hasItems;

import com.lunchcatch.auth.AuthCookieFactory;
import com.lunchcatch.auth.AuthOpenApiConfig;
import com.lunchcatch.global.config.OpenApiConfig;
import com.lunchcatch.member.contract.MemberQueryService;
import com.lunchcatch.member.oauth.KakaoAuthorizationService;
import com.lunchcatch.member.service.AdminMemberService;
import com.lunchcatch.member.service.MemberLocationService;
import com.lunchcatch.member.service.MemberLoginService;
import com.lunchcatch.member.service.MemberProfileService;
import com.lunchcatch.member.service.MemberTokenService;
import com.lunchcatch.member.service.MemberWithdrawalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocSecurityConfiguration;
import org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(excludeAutoConfiguration = OAuth2ClientWebSecurityAutoConfiguration.class, properties = {
        "spring.profiles.active=test",
        "springdoc.api-docs.path=/v3/api-docs",
        "springdoc.packages-to-scan=com.lunchcatch.member.controller"
})
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {MemberAuthController.class, MemberController.class,
        MemberProfileController.class, AdminMemberController.class, KakaoUnlinkWebhookController.class})
@Import({OpenApiConfig.class, AuthOpenApiConfig.class, SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class,
        SpringDocSpecPropertiesConfiguration.class, SpringDocSecurityConfiguration.class})
@EnableConfigurationProperties(SpringDocConfigProperties.class)
class MemberOpenApiTest {

    @Autowired MockMvc mvc;
    @MockitoBean KakaoAuthorizationService kakaoAuthorizationService;
    @MockitoBean MemberLoginService memberLoginService;
    @MockitoBean MemberTokenService memberTokenService;
    @MockitoBean MemberQueryService memberQueryService;
    @MockitoBean MemberProfileService memberProfileService;
    @MockitoBean MemberWithdrawalService memberWithdrawalService;
    @MockitoBean MemberLocationService memberLocationService;
    @MockitoBean AdminMemberService adminMemberService;
    @MockitoBean AuthCookieFactory authCookieFactory;

    @ParameterizedTest
    @CsvSource({
            "/v1/auth/kakao/authorize,get,200",
            "/v1/auth/tokens,post,200",
            "/v1/auth/tokens:refresh,post,200",
            "/v1/auth/tokens,delete,204",
            "/v1/members/me/onboarding,put,200",
            "/v1/members/me,get,200",
            "/v1/members/me,patch,200",
            "/v1/members/me:withdraw,post,204",
            "/v1/member-profiles/location,put,200",
            "/v1/member-profiles/location,get,200",
            "/v1/member-profiles/location,delete,204",
            "/v1/admin/members,get,200",
            "/webhook/kakao/unlink,get,200",
            "/webhook/kakao/unlink,post,200"
    })
    void 모든_회원_API에_설명과_성공_응답이_생성된다(String path, String method, String success) throws Exception {
        String operation = "$.paths['" + path + "']." + method;

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".summary", matchesPattern(".*[가-힣].*")))
                .andExpect(jsonPath(operation + ".description").isNotEmpty())
                .andExpect(jsonPath(operation + ".tags").isNotEmpty())
                .andExpect(jsonPath(operation + ".responses['" + success + "'].description").isNotEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "/v1/auth/tokens,delete,accessToken,accessToken",
            "/v1/members/me/onboarding,put,accessToken,accessToken",
            "/v1/members/me,get,accessToken,accessToken",
            "/v1/members/me,patch,accessToken,accessToken",
            "/v1/members/me:withdraw,post,accessToken,accessToken",
            "/v1/member-profiles/location,put,accessToken,accessToken",
            "/v1/member-profiles/location,get,accessToken,accessToken",
            "/v1/member-profiles/location,delete,accessToken,accessToken",
            "/v1/admin/members,get,adminAccessToken,adminAccessToken"
    })
    void 보호된_API에는_쿠키_인증과_권한_오류가_표시된다(String path, String method, String scheme, String cookieName) throws Exception {
        String operation = "$.paths['" + path + "']." + method;

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".security[0]['" + scheme + "']").exists())
                .andExpect(jsonPath(operation + ".responses['401'].content['application/json'].schema['$ref']", containsString("ResponseEnvelope")))
                .andExpect(jsonPath(operation + ".responses['403'].content['application/json'].schema['$ref']", containsString("ResponseEnvelope")))
                .andExpect(jsonPath("$.components.securitySchemes['" + scheme + "'].in").value("cookie"))
                .andExpect(jsonPath("$.components.securitySchemes['" + scheme + "'].name").value(cookieName));
    }

    @Test
    void 응답_없는_성공에는_본문_스키마가_없다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/v1/members/me:withdraw'].post.responses['204'].content").doesNotExist())
                .andExpect(jsonPath("$.paths['/v1/members/me/:withdraw']").doesNotExist())
                .andExpect(jsonPath("$.paths['/v1/member-profiles/location'].delete.responses['204'].content").doesNotExist())
                .andExpect(jsonPath("$.paths['/webhook/kakao/unlink'].post.responses['200'].content").doesNotExist());
    }

    @Test
    void 요청과_응답에_필수_조건과_업무_설명이_있다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.MemberOnboardingRequest.required.length()").value(4))
                .andExpect(jsonPath("$.components.schemas.MemberLocationRequest.required.length()").value(2))
                .andExpect(jsonPath("$.components.schemas.MemberLocationRequest.properties.coordinatePair").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.MemberResponse.properties.feedAvailable.description", containsString("모두 참")))
                .andExpect(jsonPath("$.components.schemas.MemberResponse.properties.profile.description", containsString("null")))
                .andExpect(jsonPath("$.components.schemas.MemberResponse.properties.profile.type").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.MemberProfileResponse.type", hasItems("object", "null")))
                .andExpect(jsonPath("$.components.schemas.MemberUpdateRequest.properties.nickname.description", containsString("1~20")));
    }
}
