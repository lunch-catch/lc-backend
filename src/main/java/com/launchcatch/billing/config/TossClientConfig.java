package com.launchcatch.billing.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
@EnableConfigurationProperties(TossProperties.class)
public class TossClientConfig {

    // 토스 전용 풀이다. 공통 풀을 쓰면 다른 외부 API가 밀릴 때 결제 승인이 같이 풀 대기에 걸린다.
    private static final int MAX_CONNECTIONS = 50;
    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(2);

    /*
     * 토스 전용 WebClient다. 기본 URL과 Basic 인증(비밀키 뒤에 콜론)을 여기서 한 번만 건다.
     *
     * 공통 커스터마이저의 커넥터는 이 빈에서 덮어쓴다. 연결과 읽기 타임아웃을 설정으로 조정해야 해서다.
     * 호출 로깅과 추적 필터는 빌더에 이미 붙어 있어 그대로 남는다. 그 로깅 필터는 경로만 남기고
     * 헤더를 찍지 않으므로 Authorization 헤더가 로그에 실리지 않는다.
     */
    @Bean
    public WebClient tossPaymentWebClient(WebClient.Builder builder, TossProperties properties) {
        ConnectionProvider pool = ConnectionProvider.builder("toss-payment")
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(PENDING_ACQUIRE_TIMEOUT)
                .build();
        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
                .responseTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeaders(headers -> headers.setBasicAuth(properties.secretKey(), ""))
                .build();
    }
}
