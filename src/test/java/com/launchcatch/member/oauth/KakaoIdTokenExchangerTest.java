package com.launchcatch.member.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.member.client.KakaoTokenClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void setUp() {
        exchanger = new KakaoIdTokenExchanger(
                clientRegistrationRepository, kakaoLoginStateRepository, kakaoTokenClient, kakaoJwtDecoder);
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
