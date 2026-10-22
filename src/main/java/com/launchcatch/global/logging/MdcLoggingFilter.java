package com.launchcatch.global.logging;

import com.launchcatch.global.http.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청의 컨텍스트(traceId, method, uri, clientIp)를 MDC 에 적재하는 필터.
 *
 * "traceId"라는 이름을 쓰지만 OpenTelemetry 같은 정식 분산 추적(distributed tracing) 라이브러리가
 * 아니다 — span 트리(부모-자식 인과관계), 자동 계측(HTTP/DB/Redis 호출마다 자동으로 구간 기록) 같은
 * 기능은 전혀 없고, 그냥 평평한 문자열 값 하나를 요청 시작 시점에 만들어서 MDC에 심어두는 게 전부인
 * 간이(simplified) 구현이다.
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

    public static final String TRACE_ID = "traceId";
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

    // 들어온 요청에 이미 X-Trace-Id가 있으면(예: 우리 스스로가 호출한 다른 내부 서비스로부터
    // 되돌아온 요청, 혹은 프론트/게이트웨이가 미리 붙여 보낸 값) 새로 안 만들고 그대로 이어받는다 —
    // 이게 있어야 여러 홉에 걸쳐 같은 값이 유지될 수 있다(MSA 환경 한정).
    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }
}
