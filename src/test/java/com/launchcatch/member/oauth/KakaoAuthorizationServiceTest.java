package com.launchcatch.member.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

@ExtendWith(MockitoExtension.class)
class KakaoAuthorizationServiceTest {

    @Mock
    private ClientRegistrationRepository clientRegistrationRepository;

    @Mock
    private KakaoLoginStateRepository kakaoLoginStateRepository;

    private KakaoAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new KakaoAuthorizationService(clientRegistrationRepository, kakaoLoginStateRepository);
        when(clientRegistrationRepository.findByRegistrationId("kakao")).thenReturn(kakaoRegistration());
    }

    @Test
    @DisplayName("회원 로그인 인가 URL에는 OIDC scope, state, nonce를 넣는다")
    void 로그인_인가_URL을_만든다() {
        UriComponents url = UriComponentsBuilder.fromUriString(service.createAuthorizationUrl(false)).build();
        Map<String, String> query = url.getQueryParams().toSingleValueMap();

        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo("kauth.kakao.com");
        assertThat(query).containsEntry("response_type", "code")
                .containsEntry("client_id", "client-id")
                .containsEntry("redirect_uri", "https://app.example.com/oauth/callback")
                .containsEntry("scope", "openid profile_nickname profile_image");
        assertThat(query.get("state")).isNotBlank();
        assertThat(query.get("nonce")).isNotBlank();
        assertThat(query).doesNotContainKey("prompt");
        verify(kakaoLoginStateRepository).save(anyString(), anyString());
    }

    @Test
    @DisplayName("재인증 인가 URL에는 prompt=login을 넣는다")
    void 재인증_인가_URL을_만든다() {
        UriComponents url = UriComponentsBuilder.fromUriString(service.createAuthorizationUrl(true)).build();

        assertThat(url.getQueryParams().getFirst("prompt")).isEqualTo("login");
    }

    private ClientRegistration kakaoRegistration() {
        return ClientRegistration.withRegistrationId("kakao")
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.example.com/oauth/callback")
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .jwkSetUri("https://kauth.kakao.com/.well-known/jwks.json")
                .build();
    }
}
