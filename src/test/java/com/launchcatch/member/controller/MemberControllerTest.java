package com.launchcatch.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.member.dto.MemberOnboardingRequest;
import com.launchcatch.member.dto.MemberUpdateRequest;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.service.MemberProfileService;
import com.launchcatch.member.service.MemberWithdrawalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MemberControllerTest {

    private final MemberProfileService profiles = mock(MemberProfileService.class);
    private final MemberWithdrawalService withdrawals = mock(MemberWithdrawalService.class);
    private final AuthCookieFactory cookies = mock(AuthCookieFactory.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CustomUserDetails user = new CustomUserDetails(42L, Role.MEMBER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        mvc = MockMvcBuilders.standaloneSetup(new MemberController(profiles, withdrawals, cookies))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 탈퇴는_명세의_콜론_경로로_호출하고_쿠키를_만료한다() throws Exception {
        when(cookies.expiredAccessTokenCookie()).thenReturn(ResponseCookie.from("accessToken", "").maxAge(0).build());
        when(cookies.expiredRefreshTokenCookie(Role.MEMBER)).thenReturn(ResponseCookie.from("refreshToken", "").maxAge(0).build());

        var result = mvc.perform(post("/v1/members/me:withdraw").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"code\",\"state\":\"state\"}"))
                .andExpect(status().isNoContent()).andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2)
                .anySatisfy(value -> assertThat(value).contains("accessToken=;", "Max-Age=0"))
                .anySatisfy(value -> assertThat(value).contains("refreshToken=;", "Max-Age=0"));

        verify(withdrawals).withdraw(42L, "code", "state");
    }

    @Test
    void 내_정보_조회_경로를_유지한다() throws Exception {
        mvc.perform(get("/v1/members/me")).andExpect(status().isOk());

        verify(profiles).getMyProfile(42L);
    }

    @Test
    void 내_정보_수정_경로를_유지한다() throws Exception {
        mvc.perform(patch("/v1/members/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"점심헌터\"}"))
                .andExpect(status().isOk());

        verify(profiles).updateMyProfile(42L, new MemberUpdateRequest("점심헌터", null, null));
    }

    @Test
    void 온보딩_경로를_유지한다() throws Exception {
        mvc.perform(put("/v1/members/me/onboarding").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gender\":\"FEMALE\",\"ageGroup\":\"AGE_20S\",\"locationOptIn\":true,\"notificationOptIn\":false}"))
                .andExpect(status().isOk());

        verify(profiles).completeOnboarding(42L, new MemberOnboardingRequest(Gender.FEMALE, AgeGroup.AGE_20S, true, false));
    }
}
