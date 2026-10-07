package com.launchcatch.member.client;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.member.oauth.KakaoTokenResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoTokenClient {

    private final WebClient kakaoApiWebClient;

    @CircuitBreaker(name = "kakaoLogin")
    public KakaoTokenResponse exchange(ClientRegistration kakao, String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakao.getClientId());
        form.add("client_secret", kakao.getClientSecret());
        form.add("redirect_uri", kakao.getRedirectUri());
        form.add("code", authorizationCode);

        try {
            KakaoTokenResponseBody body = kakaoApiWebClient.post()
                    .uri(kakao.getProviderDetails().getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(KakaoTokenResponseBody.class)
                    .block();
            if (body == null || body.id_token() == null || body.id_token().isBlank()) {
                throw new AuthException(AuthErrorCode.KAKAO_AUTHENTICATION_FAILED);
            }
            return new KakaoTokenResponse(body.id_token(), body.access_token());
        } catch (WebClientException e) {
            log.warn("event=KAKAO_TOKEN_EXCHANGE_FAILED", e);
            throw new AuthException(AuthErrorCode.KAKAO_AUTHENTICATION_FAILED, e);
        }
    }

    private record KakaoTokenResponseBody(String id_token, String access_token) {
    }
}
