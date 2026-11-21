package com.lunchcatch.global.config;

import io.prometheus.metrics.tracer.agent.OpenTelemetryAgentSpanContext;
import io.prometheus.metrics.tracer.common.SpanContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/*
 * 히스토그램의 기록에 그때 처리하던 요청의 트레이스 ID 를 exemplar 로 붙인다(#1179).
 * 트레이싱은 OTel 에이전트를 밖에서 붙여 하고 지표는 Micrometer 가 내므로, 에이전트의 현재 스팬을 직접 읽는다.
 * Spring Boot 는 이 빈이 있으면 PrometheusMeterRegistry 에 넘기고, OpenMetrics 로 긁을 때 버킷 줄 끝에 트레이스 ID 가 붙는다.
 * Micrometer Tracing 과 OTel 브리지는 앱 안에 SDK 를 하나 더 만들어 에이전트와 스팬을 이중으로 만들어 쓰지 않는다.
 * 에이전트를 붙이지 않은 앱(운영, 로컬)에서는 빈을 만들지 않아 지표가 그대로다.
 */
@Configuration
public class ExemplarConfig {

    @Bean
    @Conditional(AgentAttached.class)
    SpanContext agentSpanContext() {
        return new OpenTelemetryAgentSpanContext();
    }

    // 에이전트가 붙어 있으면 그 안의 OTel API 를 불러 낼 수 있다
    static class AgentAttached implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return OpenTelemetryAgentSpanContext.isAvailable();
        }
    }
}
