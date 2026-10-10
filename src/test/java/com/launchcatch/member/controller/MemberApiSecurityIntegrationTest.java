package com.launchcatch.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.repository.MemberRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

// /v1/members/** 의 인증과 역할 검사, 그리고 응답 시각의 오프셋을 실제 보안 체인으로 확인한다.
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberApiSecurityIntegrationTest {

    private static final String ME = "/v1/members/me";

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
    @Autowired MemberRepository memberRepository;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get(ME)).andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_토큰으로는_회원_API를_부를_수_없다() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, Role.ADMIN);

        mockMvc.perform(get(ME).cookie(new Cookie("accessToken", token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 점주_토큰으로는_회원_API를_부를_수_없다() throws Exception {
        var owner = com.launchcatch.owner.entity.Owner.create("owner-role-test@example.com", "hash");
        entityManager.persist(owner);
        entityManager.flush();
        String token = jwtTokenProvider.createAccessToken(owner.getId(), Role.OWNER);

        mockMvc.perform(get(ME).cookie(new Cookie("accessToken", token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 내_정보의_가입_시각은_KST_오프셋으로_나간다() throws Exception {
        Member member = memberRepository.saveAndFlush(Member.create("kakao-sec-1", "점심헌터", null));
        String token = jwtTokenProvider.createAccessToken(member.getId(), Role.MEMBER);

        mockMvc.perform(get(ME).cookie(new Cookie("accessToken", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.onboardingCompleted").value(false))
                .andExpect(jsonPath("$.data.feedAvailable").value(false))
                .andExpect(jsonPath("$.data.providerUserId").doesNotExist());
    }
}
