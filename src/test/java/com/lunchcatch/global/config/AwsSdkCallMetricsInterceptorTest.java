package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.s3.S3Client;

/*
 * 인터셉터는 SDK 가 클래스패스의 global/handlers/execution.interceptors 를 읽어 모든 클라이언트에 붙인다.
 * 그래서 클라이언트를 만들 때 인터셉터를 따로 넘기지 않는다. 가짜 서버로 실제 호출을 보내 그 경로를 본다.
 */
class AwsSdkCallMetricsInterceptorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void start() throws IOException {
        Metrics.addRegistry(registry);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            int status = exchange.getRequestURI().getPath().contains("missing") ? 500 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        endpoint = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        Metrics.removeRegistry(registry);
        registry.close();
    }

    @Test
    @DisplayName("AWS SDK 호출마다 서비스, 작업, 결과를 단 타이머를 남긴다")
    void SDK_호출마다_타이머를_남긴다() {
        try (S3Client s3 = S3Client.builder().endpointOverride(endpoint).forcePathStyle(true)
                .region(Region.AP_NORTHEAST_2).credentialsProvider(credentials()).build()) {
            s3.headBucket(b -> b.bucket("posters"));
            assertThatThrownBy(() -> s3.headBucket(b -> b.bucket("missing"))).isInstanceOf(SdkException.class);
        }

        Timer success = registry.get(AwsSdkCallMetricsInterceptor.METRIC)
                .tags("service", "S3", "operation", "HeadBucket", "outcome", "success", "model", "none").timer();
        Timer failure = registry.get(AwsSdkCallMetricsInterceptor.METRIC)
                .tags("service", "S3", "operation", "HeadBucket", "outcome", "failure", "model", "none").timer();
        assertThat(success.count()).isEqualTo(1);
        assertThat(failure.count()).isEqualTo(1);
    }

    // LLM 은 모델마다 정상 시간이 크게 달라 모델로 나눠 본다
    @Test
    @DisplayName("Bedrock 호출은 요청의 모델을 꼬리표로 단다")
    void Bedrock_호출은_모델을_꼬리표로_단다() {
        try (BedrockRuntimeClient bedrock = BedrockRuntimeClient.builder().endpointOverride(endpoint)
                .region(Region.AP_NORTHEAST_1).credentialsProvider(credentials()).build()) {
            assertThatThrownBy(() -> bedrock.converse(c -> c.modelId("missing-model")
                    .messages(Message.builder().role(ConversationRole.USER)
                            .content(ContentBlock.fromText("안녕")).build())))
                    .isInstanceOf(SdkException.class);
        }

        Timer converse = registry.get(AwsSdkCallMetricsInterceptor.METRIC)
                .tags("operation", "Converse", "model", "missing-model", "outcome", "failure").timer();
        assertThat(converse.count()).isEqualTo(1);
    }

    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }
}
