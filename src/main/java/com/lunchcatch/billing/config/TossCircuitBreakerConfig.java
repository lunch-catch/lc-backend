package com.lunchcatch.billing.config;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import java.util.concurrent.TimeoutException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 토스 서킷이 "이 예외를 실패로 셀지" 정하는 판단.
 *
 * auth 의 카카오 구성과 같은 모양이다. 다만 그 클래스는 auth 소유라 재사용하지 않고 여기에 따로 둔다.
 *
 * 서킷 안에서 던져지는 것은 WebClient 의 원래 예외다. PG 예외로 바꾸는 일은 서킷 바깥에서 하므로
 * 여기서는 상태 코드와 전송 단계 예외를 그대로 볼 수 있다.
 */
@Configuration
public class TossCircuitBreakerConfig {

    /** application.yml 의 resilience4j.circuitbreaker.instances 이름과 같아야 한다. */
    public static final String INSTANCE = "tossPayment";

    @Bean
    public CircuitBreakerConfigCustomizer tossPaymentFailureClassifier() {
        return CircuitBreakerConfigCustomizer.of(INSTANCE,
                builder -> builder.recordException(TossCircuitBreakerConfig::isCircuitFailure));
    }

    /*
     * 5xx, 429, 키 설정 오류, 응답을 아예 못 받은 경우(연결 실패, DNS, 타임아웃)만 실패로 센다.
     * 키 설정 오류는 모든 호출이 같은 이유로 실패하므로 서킷을 열어 헛된 호출을 멈춘다.
     * 토스는 카드사 거절과 한도 초과, FDS 차단도 403 으로 준다. 상태가 아니라 오류 코드로 가르며,
     * 이런 정상 거절이 쌓였다고 서킷이 열리면 멀쩡한 다른 결제까지 막힌다.
     *
     * 상태 코드가 없는 예외를 전부 실패로 세지 않는다. 우리 코드의 NPE나 DTO 매핑 버그가 PG 장애로 집계된다.
     * 스프링이 전송 단계에서 던지는 WebClientRequestException 과 TimeoutException 만 인정한다.
     */
    public static boolean isCircuitFailure(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException responseException) {
                HttpStatusCode status = responseException.getStatusCode();
                int code = status.value();
                return code == 429 || status.is5xxServerError()
                        || TossErrorCodes.isConfigFailure(responseException)
                        || TossErrorCodes.isUnexplainedForbidden(responseException);
            }
            if (cause instanceof WebClientRequestException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
