package com.launchcatch.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

// 어느 도메인 체인도 선언하지 않은 경로를 SecurityConfig 의 기본 체인이 거부하는지 실제 입구부터 확인한다.
class DefaultSecurityChainIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String UNDECLARED_PATH = "/v1/undeclared-path";
    private static final String UNDECLARED_ADMIN_PATH = "/v1/admin/undeclared-path";

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "OWNER", "ADMIN", "SUPER_ADMIN"})
    void 선언하지_않은_경로는_로그인한_역할도_거부한다(String role) throws Exception {
        mvc.perform(get(UNDECLARED_PATH).with(user("user").roles(role)))
                .andExpect(status().isForbidden());
        mvc.perform(post(UNDECLARED_PATH).with(user("user").roles(role)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "OWNER"})
    void 선언하지_않은_관리자_경로는_관리자가_아닌_역할을_거부한다(String role) throws Exception {
        mvc.perform(get(UNDECLARED_ADMIN_PATH).with(user("user").roles(role)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 선언하지_않은_경로는_인증이_없으면_401이다() throws Exception {
        mvc.perform(get(UNDECLARED_PATH))
                .andExpect(status().isUnauthorized());
    }

    // 컨테이너가 sendError 로 /error 에 다시 넘긴 요청이 403 으로 덮이지 않고 원래 상태로 그려지는지 본다.
    @Test
    void 오류_전달은_거부하지_않고_원래_상태_코드로_그린다() throws Exception {
        mvc.perform(get("/error")
                        .with(user("user").roles("MEMBER"))
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
                            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/v1/members/me");
                            return request;
                        }))
                .andExpect(status().isBadRequest());
    }
}
