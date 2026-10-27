package com.lunchcatch.global.config;

import io.lettuce.core.SocketOptions;
import io.lettuce.core.SocketOptions.KeepAliveOptions;
import io.lettuce.core.SocketOptions.TcpUserTimeoutOptions;
import java.time.Duration;
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
