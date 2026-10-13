package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.launchcatch.global.config.WebClientConfig;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.owner.config.OwnerSecurityConfig;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.owner.service.OwnerPlaceSearchService;
import com.launchcatch.store.client.KakaoLocalClient;
import com.launchcatch.store.config.KakaoLocalConfig;
import com.launchcatch.owner.controller.OwnerPlaceSearchController;
import com.launchcatch.store.service.KakaoPlaceSearchService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import reactor.core.publisher.Mono;

@SpringBootTest(classes = KakaoPlaceSearchIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"app.cors.allowed-origins=https://owner.example.com",
                "kakao.local.rest-api-key=test-local-key", "kakao.local.request-timeout=1s"})
class KakaoPlaceSearchIntegrationTest {
    private static final String PATH = "/v1/owner/kakao-places";
    @Autowired private WebApplicationContext context;
    @Autowired private OwnerRepository owners;
    @Autowired private AccessTokenValidAfterRepository cutoff;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private ExchangeFunction exchange;
    @Autowired private CircuitBreakerRegistry circuits;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(owners, cutoff, exchange);
        circuits.circuitBreaker("kakaoPlaceSearch").reset();
        when(cutoff.isValidAfter(any(), any(), any())).thenReturn(true);
        when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.ONBOARDING));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 온보딩_점주는_상호명만으로_장소를_조회한다() throws Exception {
        when(exchange.exchange(any())).thenReturn(json("""
                {"documents":[{"id":"123","place_name":"런치캐치 식당","phone":"",
                 "road_address_name":"서울 강남구 테헤란로 1","y":"37.5","x":"127.0"}]}
                """));

        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(Role.OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.places.length()").value(1))
                .andExpect(jsonPath("$.data.places[0].placeId").value("123"))
                .andExpect(jsonPath("$.data.places[0].placeName").value("런치캐치 식당"))
                .andExpect(jsonPath("$.data.places[0].phone").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.places[0].roadAddress").value("서울 강남구 테헤란로 1"))
                .andExpect(jsonPath("$.data.places[0].latitude").value(37.5))
                .andExpect(jsonPath("$.data.places[0].longitude").value(127.0))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void 결과가_없으면_빈_배열로_성공한다() throws Exception {
        when(exchange.exchange(any())).thenReturn(json("{\"documents\":[]}"));

        mvc.perform(get(PATH).param("query", "없는가게").cookie(token(Role.OWNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.places").isEmpty());
    }

    @Test
    void 검색어가_없으면_400이고_외부_호출을_하지_않는다() throws Exception {
        mvc.perform(get(PATH).cookie(token(Role.OWNER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        verifyNoInteractions(owners, exchange);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void 빈_검색어와_공백은_400이다(String query) throws Exception {
        mvc.perform(get(PATH).param("query", query).cookie(token(Role.OWNER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        verifyNoInteractions(owners, exchange);
    }

    @Test
    void 검색어가_100자를_초과하면_400이다() throws Exception {
        mvc.perform(get(PATH).param("query", "가".repeat(101)).cookie(token(Role.OWNER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        verifyNoInteractions(owners, exchange);
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get(PATH).param("query", "런치캐치"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(owners, exchange);
    }

    @Test
    void 잘못된_토큰은_401이다() throws Exception {
        mvc.perform(get(PATH).param("query", "런치캐치").cookie(new Cookie("accessToken", "invalid")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(owners, exchange);
    }

    @Test
    void 폐기된_토큰은_401이다() throws Exception {
        when(cutoff.isValidAfter(any(), any(), any())).thenReturn(false);
        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(Role.OWNER)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(owners, exchange);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_역할은_403이다(Role role) throws Exception {
        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(role)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        verifyNoInteractions(owners, exchange);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ACTIVE", "SUSPENDED", "WITHDRAWN"})
    void 현재_상태가_온보딩이_아니면_403이다(OwnerStatus ownerStatus) throws Exception {
        when(owners.findStatusById(7L)).thenReturn(Optional.of(ownerStatus));

        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(Role.OWNER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER-002"));
        verifyNoInteractions(exchange);
    }

    @Test
    void 점주_계정이_없으면_401이다() throws Exception {
        when(owners.findStatusById(7L)).thenReturn(Optional.empty());

        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(Role.OWNER)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        verifyNoInteractions(exchange);
    }

    @Test
    void DB_장애를_정상_응답으로_바꾸지_않는다() throws Exception {
        when(owners.findStatusById(7L)).thenThrow(new DataAccessResourceFailureException("DB unavailable"));

        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(Role.OWNER)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));
        verifyNoInteractions(exchange);
    }

    @Test
    void 카카오_장애는_503과_재시도_헤더를_반환한다() throws Exception {
        when(exchange.exchange(any())).thenReturn(Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(Role.OWNER)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORE-001"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
        assertThat(circuits.circuitBreaker("kakaoPlaceSearch").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void 카카오_인증_거부는_502이며_회로_실패로_세지_않는다() throws Exception {
        when(exchange.exchange(any())).thenReturn(Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build()));

        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(Role.OWNER)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("STORE-002"));
        assertThat(circuits.circuitBreaker("kakaoPlaceSearch").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void 전체_타임아웃은_503이며_회로_실패로_센다() throws Exception {
        when(exchange.exchange(any())).thenReturn(Mono.never());

        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(Role.OWNER)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORE-001"));
        assertThat(circuits.circuitBreaker("kakaoPlaceSearch").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void 열린_회로는_카카오를_호출하지_않고_503이다() throws Exception {
        circuits.circuitBreaker("kakaoPlaceSearch").transitionToOpenState();

        mvc.perform(get(PATH).param("query", "런치캐치").cookie(token(Role.OWNER)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORE-001"));
        verifyNoInteractions(exchange);
    }

    @Test
    void GET_외의_메서드는_허용하지_않는다() throws Exception {
        mvc.perform(post(PATH).cookie(token(Role.OWNER))).andExpect(status().isForbidden());
        verifyNoInteractions(owners, exchange);
    }

    private Cookie token(Role role) {
        return new Cookie("accessToken", jwt.createAccessToken(7L, role));
    }

    private static Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json").body(body).build());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableAspectJAutoProxy
    @ImportAutoConfiguration({WebClientAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
    @Import({OwnerSecurityConfig.class, SecurityConfig.class, ApiSecurityDefaults.class,
            OwnerPlaceSearchController.class, OwnerPlaceSearchService.class, KakaoPlaceSearchService.class, KakaoLocalClient.class,
            KakaoLocalConfig.class, WebClientConfig.class, AuthExceptionHandler.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean OwnerRepository owners() { return mock(OwnerRepository.class); }
        @Bean AccessTokenValidAfterRepository cutoff() { return mock(AccessTokenValidAfterRepository.class); }
        @Bean StringRedisTemplate redisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean ExchangeFunction exchange() { return mock(ExchangeFunction.class); }
        @Bean WebClientCustomizer stubTransport(ExchangeFunction exchange) {
            return builder -> builder.exchangeFunction(exchange);
        }
        @Bean JwtTokenProvider jwt() {
            return new JwtTokenProvider("test-only-signing-secret-with-at-least-32-bytes",
                    1800000, 86400000, 1209600000, 1209600000, Clock.systemUTC());
        }
    }
}
