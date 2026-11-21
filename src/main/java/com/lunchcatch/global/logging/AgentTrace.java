package com.lunchcatch.global.logging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/*
 * OpenTelemetry 에이전트가 지금 열어 둔 트레이스의 ID 를 읽는다(#1300).
 * 에이전트가 붙으면 앱의 API 호출이 에이전트로 이어져 진짜 트레이스가 보인다. 붙지 않으면 빈 트레이스라 null 이다.
 * 로그의 trace_id 를 이 값으로 두면 Grafana 에서 로그와 트레이스가 같은 값으로 이어진다.
 */
final class AgentTrace {

    private AgentTrace() {
    }

    static String currentTraceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : null;
    }
}
