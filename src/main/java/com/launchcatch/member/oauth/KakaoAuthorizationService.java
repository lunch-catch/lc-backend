package com.launchcatch.member.oauth;

import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class KakaoAuthorizationService {

    private static final String REGISTRATION_ID = "kakao";
    private static final String SCOPE = "openid profile_nickname profile_image";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final KakaoLoginStateRepository kakaoLoginStateRepository;

    public String createAuthorizationUrl(boolean reauth) {
        ClientRegistration kakao = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        String state = randomValue();
        String nonce = randomValue();
        kakaoLoginStateRepository.save(state, nonce);

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(kakao.getProviderDetails().getAuthorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", kakao.getClientId())
                .queryParam("redirect_uri", kakao.getRedirectUri())
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .queryParam("nonce", nonce);
        if (reauth) {
            builder.queryParam("prompt", "login");
        }
        return builder.build().toUriString();
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
