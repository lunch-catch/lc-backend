package com.lunchcatch.global.logging;

import com.lunchcatch.global.http.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청의 컨텍스트(trace_id, method, uri, clientIp)를 MDC 에 적재하는 필터.
 *
 * trace_id 는 OpenTelemetry 에이전트가 붙어 있으면 그 요청의 트레이스 ID 이고, 붙지 않았으면 이 필터가
 * 만든 값이다(#1300). 키 이름을 에이전트와 같게 두어 어느 환경이든 로그를 한 값으로 묶고, 에이전트가 있으면
 * 그 값으로 Grafana 에서 트레이스까지 넘어간다. 에이전트가 없을 때의 값은 span 트리도 자동 계측도 없는
 * 평평한 문자열 하나다.
 *
 * 이 값이 "여러 서비스에 걸친 추적"으로서 의미를 가지려면 이 값을 아웃바운드 호출에 실어 보내고
 * (TraceIdExchangeFilter), 받는 쪽 서비스도 그 값을 이어받아 쓰는 구조가 돼야 한다 — 즉 MSA
 * 환경에서만 실제 값어치가 생긴다. 이 프로젝트는 지금 모놀리스 하나라 서비스 경계를 넘는 호출이
 * 카카오(외부, 우리 규약을 안 따름)뿐이라, 지금은 "한 프로세스 안에서 요청 1건의 로그를 묶는" 용도로만
 * 쓰이고 있다 — 그 자체로도 값어치가 있다(HTTP 계층 로그와 비즈니스 계층 로그를 이어주고, 아직 엔티티가
 * 특정되기 전 단계의 로그까지 커버하는 등, memberId/campaignId 같은 업무 식별자로는 못 하는 역할).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "trace_id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[a-zA-Z0-9\\-_.]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String traceId = (String) request.getAttribute(TRACE_ID);
        if (traceId == null) {
            traceId = resolveTraceId(request);
            request.setAttribute(TRACE_ID, traceId);
        }

        try {
            MDC.put(TRACE_ID, traceId);
            MDC.put("method", request.getMethod());
            MDC.put("uri", request.getRequestURI());
            MDC.put("clientIp", ClientIpResolver.resolve(request));

            response.setHeader(TRACE_ID_HEADER, traceId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /*
     * 에이전트가 이 요청의 서버 스팬을 열었으면 그 트레이스 ID 를 쓴다. 로그와 트레이스가 같은 값으로 이어진다(#1300).
     * 없으면 들어온 X-Trace-Id 를 이어받고(프론트나 게이트웨이가 붙여 보낸 값), 그것도 없으면 새로 만든다.
     */
    private String resolveTraceId(HttpServletRequest request) {
        String agentTraceId = AgentTrace.currentTraceId();
        if (agentTraceId != null) {
            return agentTraceId;
        }
        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return LogContext.newTraceId();
    }
}
