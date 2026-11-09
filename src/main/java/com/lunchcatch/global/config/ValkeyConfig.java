package com.lunchcatch.global.config;

import io.lettuce.core.SocketOptions;
import io.lettuce.core.SocketOptions.KeepAliveOptions;
import io.lettuce.core.SocketOptions.TcpUserTimeoutOptions;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * 캐시 연결이 상대 쪽에서 끊겼을 때 앱이 그것을 스스로 알아채게 한다.
 * Lettuce 는 기본으로 TCP keepalive 를 켜지 않아서, 연결이 반쯤 끊기면 커맨드가 매번 1초 타임아웃으로
 * 실패하면서도 재연결을 하지 않는다. 운영 카카오 로그인이 재시작 전까지 계속 실패한 원인이다 (#964).
 * keepalive 는 유휴 연결의 죽음을, TCP_USER_TIMEOUT 은 보낸 데이터에 응답이 없는 연결의 죽음을 잡는다.
 * 어느 쪽이든 연결이 닫히면 Lettuce 의 자동 재연결이 새 연결을 맺는다.
 */
@Configuration
public class ValkeyConfig {

    static final Duration KEEP_ALIVE_IDLE = Duration.ofSeconds(30);
    static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(10);
    static final int KEEP_ALIVE_COUNT = 3;
    static final Duration TCP_USER_TIMEOUT = Duration.ofSeconds(10);

    /*
     * Boot 가 만든 SocketOptions 를 통째로 갈아끼우는 자리라 connect-timeout 을 여기서 다시 넣는다.
     * 빼먹으면 yml 의 1초가 사라지고 Lettuce 기본 10초로 돌아간다.
     */
    @Bean
    LettuceClientOptionsBuilderCustomizer valkeySocketOptionsCustomizer(DataRedisProperties properties) {
        return builder -> builder.socketOptions(socketOptions(properties.getConnectTimeout()));
    }

    /*
     * Valkey 명령의 앱 쪽 시간을 Lettuce 자체의 지연 기록기로 남긴다(#1180).
     * Spring Boot 의 Lettuce 관측은 명령 하나마다 관측을 만들고 도는 중인 명령까지 추적해 무거워 application.yml 에서 끈다.
     * 이 기록기는 명령 이름과 원격 주소마다 타이머 하나에 완료 시간을 더한다(lettuce_command_completion_seconds).
     * 앱 쪽 시간은 서버 쪽 지표(redis-exporter)와 견주어 대기가 서버에 있는지 앱에 있는지 가르는 데 쓴다.
     * 분위수와 히스토그램은 끈다. 명령마다 계산하는 값이 늘고, 꼬리는 최대와 트레이스로 본다.
     * 로컬 주소 구분도 끈다. 연결마다 시계열이 생긴다.
     */
    @Bean
    ClientResourcesBuilderCustomizer valkeyCommandLatencyCustomizer(MeterRegistry registry) {
        MicrometerOptions options = MicrometerOptions.builder()
                .histogram(false)
                .targetPercentiles(new double[0])
                .localDistinction(false)
                .build();
        return builder -> builder.commandLatencyRecorder(new MicrometerCommandLatencyRecorder(registry, options));
    }

    static SocketOptions socketOptions(Duration connectTimeout) {
        SocketOptions.Builder builder = SocketOptions.builder()
                .keepAlive(KeepAliveOptions.builder()
                        .enable()
                        .idle(KEEP_ALIVE_IDLE)
                        .interval(KEEP_ALIVE_INTERVAL)
                        .count(KEEP_ALIVE_COUNT)
                        .build())
                .tcpUserTimeout(TcpUserTimeoutOptions.builder()
                        .enable()
                        .tcpUserTimeout(TCP_USER_TIMEOUT)
                        .build());
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        }
        return builder.build();
    }
}
