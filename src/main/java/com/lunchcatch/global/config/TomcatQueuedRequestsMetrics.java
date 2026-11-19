package com.lunchcatch.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/*
 * 톰캣에서 작업 스레드를 기다리는 요청 수를 낸다(#1272).
 * 스레드가 모두 차면 사용률은 100% 에서 더 오르지 않아, 포화가 얼마나 심한지는 기다리는 요청 수로만 보인다.
 * 연결이 읽을 요청을 받으면 톰캣은 그 처리를 작업 실행기에 넘기고, 남은 스레드가 없으면 실행기 대기열에서 기다린다. 그 대기열의 길이를 읽는다.
 * 최대 연결 수를 넘어 운영체제의 접속 대기열에 있는 연결은 톰캣이 아직 받지 않아 세지 않는다.
 */
@Component
public class TomcatQueuedRequestsMetrics implements ApplicationListener<WebServerInitializedEvent> {

    static final String METRIC = "tomcat.threads.queued";
    // 액추에이터(8081)는 따로 뜬 서버다. 요청을 받는 서버만 센다
    private static final String MANAGEMENT = "management";

    private final MeterRegistry registry;

    public TomcatQueuedRequestsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (!MANAGEMENT.equals(event.getApplicationContext().getServerNamespace())) {
            bind(event.getWebServer());
        }
    }

    void bind(WebServer webServer) {
        if (!(webServer instanceof TomcatWebServer tomcat)) {
            return;
        }
        for (Connector connector : tomcat.getTomcat().getService().findConnectors()) {
            if (connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol
                    && protocol.getExecutor() instanceof ThreadPoolExecutor executor) {
                Gauge.builder(METRIC, executor, pool -> pool.getQueue().size())
                        // 정한 포트로 뜨면 기존 tomcat_threads_busy_threads 와 같은 꼬리표다. 톰캣은 JMX 이름이라 따옴표를 붙여 돌려준다
                        .tag("name", protocol.getName().replace("\"", ""))
                        .baseUnit("requests")
                        .description("작업 스레드를 기다리며 톰캣 실행기 대기열에 있는 요청 수")
                        .register(registry);
            }
        }
    }
}
