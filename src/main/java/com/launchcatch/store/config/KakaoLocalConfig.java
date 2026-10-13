package com.launchcatch.store.config;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Configuration(proxyBeanMethods = false)
public class KakaoLocalConfig {
    @Bean
    public WebClient kakaoLocalWebClient(WebClient.Builder builder,
                                        @Value("${kakao.local.rest-api-key}") String restApiKey) {
        return builder.clone()
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey.strip())
                .build();
    }

    @Bean
    public CircuitBreakerConfigCustomizer kakaoPlaceSearchFailureClassifier() {
        return CircuitBreakerConfigCustomizer.of("kakaoPlaceSearch",
                builder -> builder.recordException(KakaoLocalConfig::isCircuitFailure));
    }

    private static boolean isCircuitFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException response) {
                return response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError();
            }
            if (cause instanceof WebClientRequestException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
