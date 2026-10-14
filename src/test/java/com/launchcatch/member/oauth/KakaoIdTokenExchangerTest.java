package com.launchcatch.member.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.member.client.KakaoTokenClient;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class KakaoIdTokenExchangerTest {

    @Mock
    private ClientRegistrationRepository clientRegistrationRepository;
    @Mock
    private KakaoLoginStateRepository kakaoLoginStateRepository;
    @Mock
    private KakaoTokenClient kakaoTokenClient;
    @Mock
    private JwtDecoder kakaoJwtDecoder;
    @Mock
    private Jwt idToken;

    private KakaoIdTokenExchanger exchanger;
    private Logger logger;
    private Level originalLevel;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(KakaoIdTokenExchanger.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
        exchanger = new KakaoIdTokenExchanger(
                clientRegistrationRepository, kakaoLoginStateRepository, kakaoTokenClient, kakaoJwtDecoder);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
        logs.stop();
    }

    @Test
    void 서킷_차단은_토큰_검증_실패와_구분한다() {
        ClientRegistration registration = ready();
        CallNotPermittedException failure = CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("kakaoLogin"));
        when(kakaoTokenClient.exchange(registration, "code")).thenThrow(failure);

        assertThatThrownBy(() -> exchanger.exchange("code", "state")).isInstanceOf(AuthException.class).hasCause(failure);

        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("reason=CIRCUIT_OPEN", "CallNotPermittedException")
                        .doesNotContain("TOKEN_VALIDATION_FAILED"));
    }

    @Test
    void 토큰_검증_실패의_원인_종류를_남기되_메시지는_제외한다() {
        ClientRegistration registration = ready();
        when(kakaoTokenClient.exchange(registration, "code")).thenReturn(new KakaoTokenResponse("id-token", "access"));
        JwtException failure = new JwtException("토큰원문", new IllegalStateException("개인정보원문"));
        when(kakaoJwtDecoder.decode("id-token")).thenThrow(failure);

        assertThatThrownBy(() -> exchanger.exchange("code", "state")).isInstanceOf(AuthException.class).hasCause(failure);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("reason=TOKEN_VALIDATION_FAILED", "JwtException", "IllegalStateException")
                    .doesNotContain("원문");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void 필수_클레임_누락은_별도_사유로_기록한다() {
        ClientRegistration registration = ready();
        when(kakaoTokenClient.exchange(registration, "code")).thenReturn(new KakaoTokenResponse("id-token", "access"));
        when(kakaoJwtDecoder.decode("id-token")).thenReturn(idToken);
        when(idToken.getClaimAsString("nonce")).thenReturn("nonce");
        when(idToken.getClaims()).thenReturn(Map.of());

        assertThatThrownBy(() -> exchanger.exchange("code", "state")).isInstanceOf(AuthException.class);

        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("reason=TOKEN_CLAIMS_INVALID"));
    }

    private ClientRegistration ready() {
        when(kakaoLoginStateRepository.consume("state")).thenReturn("nonce");
        ClientRegistration registration = registration();
        when(clientRegistrationRepository.findByRegistrationId("kakao")).thenReturn(registration);
        return registration;
    }

    @Test
    @DisplayName("소비할 state가 없으면 카카오 인증을 거부한다")
    void state가_없으면_거부한다() {
        when(kakaoLoginStateRepository.consume("state")).thenReturn(null);

        assertThatThrownBy(() -> exchanger.exchange("code", "state"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("카카오 인증");

        verify(kakaoTokenClient, never()).exchange(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("state의 nonce와 ID Token nonce가 일치할 때 카카오 회원을 얻는다")
    void nonce가_일치하면_회원_정보를_얻는다() {
        when(kakaoLoginStateRepository.consume("state")).thenReturn("nonce");
        ClientRegistration registration = registration();
        when(clientRegistrationRepository.findByRegistrationId("kakao")).thenReturn(registration);
        when(kakaoTokenClient.exchange(registration, "code")).thenReturn(new KakaoTokenResponse("id-token", "access"));
        when(kakaoJwtDecoder.decode("id-token")).thenReturn(idToken);
        when(idToken.getClaimAsString("nonce")).thenReturn("nonce");
        when(idToken.getSubject()).thenReturn("12345");
        when(idToken.getClaims()).thenReturn(Map.of("nickname", "점심헌터", "picture", "https://image"));

        KakaoIdentity identity = exchanger.exchange("code", "state");

        assertThat(identity).isEqualTo(new KakaoIdentity("12345", "점심헌터", "https://image"));
    }

    @Test
    @DisplayName("ID Token nonce가 다르면 카카오 인증을 거부한다")
    void nonce가_다르면_거부한다() {
        when(kakaoLoginStateRepository.consume("state")).thenReturn("expected");
        ClientRegistration registration = registration();
        when(clientRegistrationRepository.findByRegistrationId("kakao")).thenReturn(registration);
        when(kakaoTokenClient.exchange(registration, "code")).thenReturn(new KakaoTokenResponse("id-token", "access"));
        when(kakaoJwtDecoder.decode("id-token")).thenReturn(idToken);
        when(idToken.getClaimAsString("nonce")).thenReturn("different");

        assertThatThrownBy(() -> exchanger.exchange("code", "state"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("카카오 인증");
    }

    private ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("kakao")
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.example.com/oauth/callback")
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .jwkSetUri("https://kauth.kakao.com/.well-known/jwks.json")
                .build();
    }
}
