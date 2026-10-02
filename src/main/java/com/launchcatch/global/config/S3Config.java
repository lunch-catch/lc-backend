package com.launchcatch.global.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * 파일 업로드가 쓰는 S3 클라이언트. presigned PUT 발급은 S3Presigner 가,
 * HeadObject/DeleteObject 같은 직접 호출은 S3Client 가 맡는다.
 * 증빙 서류(37행, 38행)와 가게 이미지(41행), 포스터 이미지(54행)가 같은 경로를 쓴다.
 * 증빙 서류는 관리자와 해당 점주만 10분 Presigned URL 로 본다(비기능 27행).
 * 둘 다 같은 리전 하나만 보므로 리전을 한 곳에서만 읽는다.
 */
@Configuration
public class S3Config {

    /*
     * AWS SDK v2 는 apiCallTimeout 과 apiCallAttemptTimeout 을 기본적으로 꺼 둔다(무제한).
     * S3 가 응답을 안 주면 호출이 사실상 무기한 걸릴 수 있다. 업로드 확인 호출을 DB 트랜잭션
     * 밖에서 하더라도, 호출 자체가 무기한 걸리는 상황은 따로 막아야 한다. 안 그러면 그 요청을
     * 처리하던 스레드와 커넥션이 응답 없는 S3 를 무기한 기다린다.
     * WebClientConfig 가 외부 호출에 명시적 타임아웃을 두는 것과 같은 이유다.
     */
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);

    /*
     * apiCallAttemptTimeout 은 호출 전체(연결, 전송, 수신)의 상한이라 TCP 연결 자체가 못 열리는
     * 상황을 따로 빨리 잡아내지 못한다. 그래서 HTTP 클라이언트에 연결 타임아웃을 별도로 둔다.
     */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    public S3Presigner s3Presigner(@Value("${s3.region}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }

    @Bean
    public S3Client s3Client(@Value("${s3.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(CONNECTION_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .build())
                .build();
    }
}
