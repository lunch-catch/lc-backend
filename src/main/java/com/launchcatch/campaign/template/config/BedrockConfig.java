package com.launchcatch.campaign.template.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/*
 * 템플릿 생성 LLM 호출(Bedrock Converse)이 쓰는 클라이언트다.
 *
 * bedrock.model-id가 없으면(로컬 등) 이 빈을 만들지 않는다. application.yml에는 이 값을
 * 기본값 있는 placeholder로 적지 않아서, 환경변수 BEDROCK_MODEL_ID를 안 넣으면 정말 "없음"이
 * 되고 FakeTemplateHtmlGenerator가 대신 뜬다. BedrockTemplateHtmlGenerator도 같은 조건을 써서
 * 둘 중 하나만 뜨게 한다.
 *
 * S3Config와 같은 이유로 apiCallAttemptTimeout/apiCallTimeout을 명시한다. SDK 기본은
 * 무제한이라 Bedrock이 응답을 안 주면 요청 스레드가 무기한 묶인다. 상한은 30초로
 * 둔다(#182, POSTER-004).
 *
 * SDK 자체의 일시적 오류 재시도는 끈다(maxAttempts(1)). 켜 두면 BedrockTemplateHtmlGenerator가
 * ThrottlingException 등을 "즉시 실패"로 처리하기로 한 결정(#182 코드 리뷰)이 무의미해진다.
 * SDK가 그 예외가 올라오기 전에 보이지 않게 몇 번 재시도할 수 있어서다. 재시도는 전부
 * 애플리케이션 레벨(1회, 내용 교정용)로만 센다. RetryPolicy는 전체가 deprecated라
 * StandardRetryStrategy를 쓴다.
 */
@Configuration
public class BedrockConfig {

    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(30);

    /** 도쿄 리전을 서울 인프라에서 건너 불러서, S3Config의 1초보다 여유를 둔다. */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    @ConditionalOnProperty(name = "bedrock.model-id")
    public BedrockRuntimeClient bedrockRuntimeClient(@Value("${bedrock.region}") String region) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(CONNECTION_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                        .build())
                .build();
    }
}
