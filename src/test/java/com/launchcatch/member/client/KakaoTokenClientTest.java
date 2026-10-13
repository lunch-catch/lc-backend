package com.launchcatch.member.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.launchcatch.auth.exception.AuthException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class KakaoTokenClientTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(KakaoTokenClient.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void attachLogs() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
        logs.stop();
    }

    @Test
    void 카카오_오류_응답의_상태는_남기고_본문은_남기지_않는다() {
        KakaoTokenClient client = client(HttpStatus.TOO_MANY_REQUESTS, "토큰과개인정보원문");

        assertThatThrownBy(() -> client.exchange(registration(), "인가코드원문")).isInstanceOf(AuthException.class);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("reason=EXTERNAL_RESPONSE_REJECTED", "httpStatus=429")
                    .doesNotContain("원문");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void 식별_토큰이_없는_응답의_실패는_한_번만_기록한다() {
        KakaoTokenClient client = client(HttpStatus.OK, "{}");

        assertThatThrownBy(() -> client.exchange(registration(), "인가코드원문")).isInstanceOf(AuthException.class);

        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("reason=ID_TOKEN_MISSING"));
    }

    @Test
    void 응답_해석_실패는_원인_정보를_기록하고_예외를_유지한다() {
        KakaoTokenClient client = client(HttpStatus.OK, "개인정보원문");

        assertThatThrownBy(() -> client.exchange(registration(), "인가코드원문")).isInstanceOf(RuntimeException.class);

        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("reason=RESPONSE_PROCESSING_FAILED", "causeTypes=")
                        .doesNotContain("원문"));
    }

    private KakaoTokenClient client(HttpStatus status, String body) {
        ClientResponse response = ClientResponse.create(status).header("Content-Type", "application/json").body(body).build();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(response)).build();
        return new KakaoTokenClient(webClient);
    }

    private ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("kakao").clientId("client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.example.com/callback")
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token").build();
    }
}
