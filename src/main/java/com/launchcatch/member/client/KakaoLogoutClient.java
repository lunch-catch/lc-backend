package com.launchcatch.member.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLogoutClient {

    private static final String LOGOUT_URI = "https://kapi.kakao.com/v1/user/logout";

    private final WebClient kakaoApiWebClient;

    @Value("${kakao.admin-key:}")
    private String adminKey;

    @CircuitBreaker(name = "kakaoLogout")
    public void logout(String providerUserId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", providerUserId);
        try {
            kakaoApiWebClient.post()
                    .uri(LOGOUT_URI)
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (RuntimeException e) {
            log.warn("event=KAKAO_LOGOUT_FAILED", e);
        }
    }
}
