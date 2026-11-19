package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.tomcat.TomcatMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.stream.IntStream;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;

/*
 * 작업 스레드를 하나로 줄인 실제 톰캣에 요청을 셋 보낸다.
 * 첫 요청이 스레드를 붙잡고 있는 동안 나머지 둘이 실행기 대기열에서 기다린다.
 */
class TomcatQueuedRequestsMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);
    private final HttpClient client = HttpClient.newHttpClient();
    private TomcatWebServer server;

    /*
     * 포트를 정해 띄운다. 앱도 8080 처럼 정한 포트로 뜬다.
     * 0 으로 띄우면 기존 톰캣 지표는 포트를 정하기 전의 이름(http-nio-auto-1)을 쓰고 커넥터는 포트를 붙인 이름을 돌려줘 두 꼬리표가 갈린다.
     */
    @BeforeEach
    void start() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(port);
        // 앱은 server.tomcat.mbeanregistry.enabled 로 켠다. 기존 톰캣 지표는 이 레지스트리에서 읽는다
        factory.setDisableMBeanRegistry(false);
        factory.addConnectorCustomizers(connector -> {
            AbstractProtocol<?> protocol = (AbstractProtocol<?>) connector.getProtocolHandler();
            protocol.setMaxThreads(1);
            protocol.setMinSpareThreads(1);
        });
        server = (TomcatWebServer) factory.getWebServer(context -> context
                .addServlet("hold", new HttpServlet() {
                    @Override
                    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                })
                .addMapping("/"));
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop();
    }

    @Test
    void 작업_스레드가_모두_차면_기다리는_요청_수가_오르고_풀리면_0으로_돌아온다() throws Exception {
        new TomcatQueuedRequestsMetrics(registry).bind(server);
        double idle = queued();

        CompletableFuture<?>[] calls = IntStream.range(0, 3).mapToObj(i -> call()).toArray(CompletableFuture[]::new);
        double saturated = waitFor(this::queued, 2);
        release.countDown();
        CompletableFuture.allOf(calls).get(10, TimeUnit.SECONDS);

        assertThat(idle).isZero();
        assertThat(saturated).isEqualTo(2);
        assertThat(waitFor(this::queued, 0)).isZero();
    }

    // 앱처럼 MBean 레지스트리를 켜고 Micrometer 의 톰캣 지표를 함께 묶어, 같은 서버의 쓰는 스레드 지표와 꼬리표 값을 견준다
    @Test
    void 기존_톰캣_지표와_같은_name_꼬리표를_단다() {
        new TomcatMetrics(null, Tags.empty()).bindTo(registry);
        new TomcatQueuedRequestsMetrics(registry).bind(server);

        assertThat(registry.get(TomcatQueuedRequestsMetrics.METRIC).gauge().getId().getTag("name"))
                .isEqualTo(registry.get("tomcat.threads.busy").gauge().getId().getTag("name"));
    }

    @Test
    void 액추에이터_서버는_세지_않고_요청을_받는_서버만_센다() {
        TomcatQueuedRequestsMetrics metrics = new TomcatQueuedRequestsMetrics(registry);

        metrics.onApplicationEvent(initialized("management"));
        boolean afterManagement = registry.find(TomcatQueuedRequestsMetrics.METRIC).gauge() != null;
        metrics.onApplicationEvent(initialized(null));

        assertThat(afterManagement).isFalse();
        assertThat(registry.find(TomcatQueuedRequestsMetrics.METRIC).gauge()).isNotNull();
    }

    private WebServerInitializedEvent initialized(String namespace) {
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        given(context.getServerNamespace()).willReturn(namespace);
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        given(event.getApplicationContext()).willReturn(context);
        given(event.getWebServer()).willReturn(server);
        return event;
    }

    private double queued() {
        Gauge gauge = registry.get(TomcatQueuedRequestsMetrics.METRIC).gauge();
        return gauge.value();
    }

    private CompletableFuture<HttpResponse<Void>> call() {
        return client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + "/")).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    // 요청이 대기열에 들어가고 빠지는 것은 톰캣 스레드에서 일어나 잠시 기다린다
    private static double waitFor(DoubleSupplier value, double expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (value.getAsDouble() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return value.getAsDouble();
    }
}
