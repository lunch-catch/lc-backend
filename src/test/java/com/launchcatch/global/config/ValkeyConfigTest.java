package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;

class ValkeyConfigTest {

    @Test
    @DisplayName("캐시 연결에 keepalive 와 TCP_USER_TIMEOUT 이 켜지고 connect-timeout 은 그대로다")
    void 캐시_연결은_끊기면_스스로_알아챈다() {
        SocketOptions options = customizedSocketOptions(Duration.ofSeconds(1));

        assertThat(options.getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(options.isKeepAlive()).isTrue();
        assertThat(options.getKeepAlive().getIdle()).isEqualTo(ValkeyConfig.KEEP_ALIVE_IDLE);
        assertThat(options.getKeepAlive().getInterval()).isEqualTo(ValkeyConfig.KEEP_ALIVE_INTERVAL);
        assertThat(options.getKeepAlive().getCount()).isEqualTo(ValkeyConfig.KEEP_ALIVE_COUNT);
        assertThat(options.getTcpUserTimeout().isEnabled()).isTrue();
        assertThat(options.getTcpUserTimeout().getTcpUserTimeout()).isEqualTo(ValkeyConfig.TCP_USER_TIMEOUT);
    }

    @Test
    @DisplayName("connect-timeout 을 주지 않으면 Lettuce 기본값을 쓴다")
    void connect_timeout_이_없으면_기본값이다() {
        SocketOptions options = customizedSocketOptions(null);

        assertThat(options.getConnectTimeout()).isEqualTo(SocketOptions.DEFAULT_CONNECT_TIMEOUT_DURATION);
    }

    private static SocketOptions customizedSocketOptions(Duration connectTimeout) {
        DataRedisProperties properties = new DataRedisProperties();
        properties.setConnectTimeout(connectTimeout);
        ClientOptions.Builder builder = ClientOptions.builder();
        new ValkeyConfig().valkeySocketOptionsCustomizer(properties).customize(builder);
        return builder.build().getSocketOptions();
    }
}
