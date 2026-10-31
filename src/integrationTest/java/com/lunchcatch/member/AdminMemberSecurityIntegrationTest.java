package com.lunchcatch.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

// GET /v1/admin/members 를 member 보안 체인이 맡고 관리자 역할만 통과시키는지 실제 입구부터 확인한다.
class AdminMemberSecurityIntegrationTest extends AbstractMemberIntegrationTest {

    private static final String PATH = "/v1/admin/members";

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("memberFilterChain")
    private SecurityFilterChain memberFilterChain;

    @Test
    void 관리자_사용자_목록은_member_보안_체인이_맡는다() {
        assertThat(memberFilterChain.matches(new MockHttpServletRequest("GET", PATH))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN"})
    void 관리자는_사용자_목록을_조회한다(String role) throws Exception {
        mvc.perform(get(PATH).with(user("admin").roles(role)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "OWNER"})
    void 관리자가_아니면_사용자_목록을_조회할_수_없다(String role) throws Exception {
        mvc.perform(get(PATH).with(user("user").roles(role)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 인증이_없으면_사용자_목록은_401이다() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }
}
