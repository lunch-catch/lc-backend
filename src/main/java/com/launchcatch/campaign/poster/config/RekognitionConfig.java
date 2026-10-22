package com.launchcatch.campaign.poster.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.rekognition.RekognitionClient;

/*
 * 포스터 자동 검수가 이미지 속 글자를 찾는 Rekognition 클라이언트다(#535).
 * 리전은 S3 와 같은 값을 쓴다. Rekognition 이 S3 이미지를 직접 읽으려면 버킷과 같은 리전이어야 하고,
 * 서버 역할 권한도 aws:RequestedRegion 으로 그 리전만 연다(lc-infra #9).
 * SDK 자체 재시도는 끈다. 검수 실패는 재시도하지 않고 FAIL 로 끝내기로 해서(명세서 57행),
 * SDK 가 보이지 않게 몇 번 다시 부르면 그만큼 활성화 요청만 길어진다.
 */
@Configuration
public class RekognitionConfig {

    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    public RekognitionClient rekognitionClient(@Value("${s3.region}") String region) {
        return RekognitionClient.builder()
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
