package com.launchcatch.owner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.launchcatch.auth.ApiSecurityDefaults;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.SecurityConfig;
import com.launchcatch.auth.exception.AuthExceptionHandler;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.owner.config.OwnerSecurityConfig;
import com.launchcatch.owner.controller.OwnerProfileController;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.owner.service.OwnerProfileService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringBootTest(classes = OwnerProfileSecurityIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "app.cors.allowed-origins=https://owner.example.com")
class OwnerProfileSecurityIntegrationTest {
    private static final String SECRET = "test-only-signing-secret-with-at-least-32-bytes";
    private static final String PATH = "/v1/owner/me";
    @Autowired private WebApplicationContext context;
    @Autowired private OwnerRepository owners;
    @Autowired private AccessTokenValidAfterRepository cutoff;
    @Autowired private JwtTokenProvider jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(owners, cutoff);
        when(cutoff.isValidAfter(any(), any(), any())).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ONBOARDING", "ACTIVE"})
    void 쿠키로_인증한_본인의_정보만_조회한다(OwnerStatus ownerStatus) throws Exception {
        Owner owner = Owner.create("owner@example.com", "password-hash");
        ReflectionTestUtils.setField(owner, "status", ownerStatus);
        ReflectionTestUtils.setField(owner, "tutorialViewed", ownerStatus == OwnerStatus.ACTIVE);
        when(owners.findById(7L)).thenReturn(Optional.of(owner));

        mvc.perform(get(PATH).param("ownerId", "99")
                        .cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value(ownerStatus.name()))
                .andExpect(jsonPath("$.data.tutorialViewed").value(ownerStatus == OwnerStatus.ACTIVE))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.refreshTokenHash").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(owners).findById(7L);
        verifyNoMoreInteractions(owners);
    }

    @Test
    void 쿠키가_없으면_401이다() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(owners);
    }

    @Test
    void 잘못된_쿠키는_401이다() throws Exception {
        mvc.perform(get(PATH).cookie(new Cookie("accessToken", "invalid-token")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(owners);
    }

    @Test
    void 만료된_실제_JWT는_401이다() throws Exception {
        var expired = new JwtTokenProvider(SECRET, 1800000, 86400000, 1209600000, 1209600000,
                Clock.offset(Clock.systemUTC(), Duration.ofHours(-2)));
        mvc.perform(get(PATH).cookie(new Cookie("accessToken", expired.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(owners);
    }

    @Test
    void 무효화된_토큰은_401이다() throws Exception {
        when(cutoff.isValidAfter(any(), any(), any())).thenReturn(false);
        mvc.perform(get(PATH).cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(owners);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(get(PATH).cookie(new Cookie("accessToken", jwt.createAccessToken(7L, role))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        verifyNoInteractions(owners);
    }

    @Test
    void 계정이_없으면_401이다() throws Exception {
        when(owners.findById(7L)).thenReturn(Optional.empty());
        mvc.perform(get(PATH).cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @Test
    void 조회_경로의_다른_메서드는_허용하지_않는다() throws Exception {
        mvc.perform(post(PATH).cookie(new Cookie("accessToken", jwt.createAccessToken(7L, Role.OWNER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(owners);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({OwnerSecurityConfig.class, SecurityConfig.class, ApiSecurityDefaults.class,
            OwnerProfileController.class, OwnerProfileService.class,
            AuthExceptionHandler.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean OwnerRepository owners() { return mock(OwnerRepository.class); }
        @Bean AccessTokenValidAfterRepository cutoff() { return mock(AccessTokenValidAfterRepository.class); }
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean JwtTokenProvider jwt() {
            return new JwtTokenProvider(SECRET, 1800000, 86400000, 1209600000, 1209600000,
                    Clock.system(ZoneId.of("Asia/Seoul")));
        }
    }
}
