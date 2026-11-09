package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.metrics.CommandLatencyRecorder;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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

    @Test
    @DisplayName("Valkey 명령의 완료 시간을 명령 이름과 원격 주소로 나눠 타이머로 남긴다")
    void 명령의_완료_시간을_타이머로_남긴다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommandLatencyRecorder recorder = customizedLatencyRecorder(registry);

        recorder.recordCommandLatency(new InetSocketAddress("app", 40000), new InetSocketAddress("valkey", 6379),
                CommandType.GET, TimeUnit.MILLISECONDS.toNanos(1), TimeUnit.MILLISECONDS.toNanos(3));

        Timer completion = registry.get("lettuce.command.completion").tag("command", "GET").timer();
        assertThat(completion.count()).isEqualTo(1);
        assertThat(completion.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(3.0);
    }

    // 분위수는 앱이 계산하지 않는다. Prometheus 가 필요하면 합계와 수로 평균을, 최대로 꼬리를 본다
    @Test
    @DisplayName("명령 타이머는 분위수와 히스토그램을 내지 않는다")
    void 명령_타이머는_분위수를_내지_않는다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommandLatencyRecorder recorder = customizedLatencyRecorder(registry);

        recorder.recordCommandLatency(new InetSocketAddress("app", 40000), new InetSocketAddress("valkey", 6379),
                CommandType.GET, 1, 1);

        Timer completion = registry.get("lettuce.command.completion").timer();
        assertThat(completion.takeSnapshot().percentileValues()).isEmpty();
        assertThat(completion.takeSnapshot().histogramCounts()).isEmpty();
    }

    // 로컬 주소를 꼬리표로 두면 연결마다 시계열이 생긴다
    @Test
    @DisplayName("명령 타이머는 로컬 주소로 나누지 않는다")
    void 명령_타이머는_로컬_주소로_나누지_않는다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommandLatencyRecorder recorder = customizedLatencyRecorder(registry);

        recorder.recordCommandLatency(new InetSocketAddress("app", 40000), new InetSocketAddress("valkey", 6379),
                CommandType.GET, 1, 1);
        recorder.recordCommandLatency(new InetSocketAddress("app", 40001), new InetSocketAddress("valkey", 6379),
                CommandType.GET, 1, 1);

        assertThat(registry.find("lettuce.command.completion").tag("command", "GET").timers()).hasSize(1);
    }

    private static CommandLatencyRecorder customizedLatencyRecorder(SimpleMeterRegistry registry) {
        DefaultClientResources.Builder builder = DefaultClientResources.builder();
        new ValkeyConfig().valkeyCommandLatencyCustomizer(registry).customize(builder);
        ClientResources resources = builder.build();
        CommandLatencyRecorder recorder = resources.commandLatencyRecorder();
        resources.shutdown();
        return recorder;
    }

    private static SocketOptions customizedSocketOptions(Duration connectTimeout) {
        DataRedisProperties properties = new DataRedisProperties();
        properties.setConnectTimeout(connectTimeout);
        ClientOptions.Builder builder = ClientOptions.builder();
        new ValkeyConfig().valkeySocketOptionsCustomizer(properties).customize(builder);
        return builder.build().getSocketOptions();
    }
}
