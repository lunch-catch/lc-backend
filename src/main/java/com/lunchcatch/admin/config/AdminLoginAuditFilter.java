package com.lunchcatch.admin.config;

import com.lunchcatch.global.http.ClientIpResolver;
import com.lunchcatch.admin.AdminLoginAuditContext;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/*
 * 관리자 로그인은 비밀번호를 보호하기 위해 일반 본문 로깅에서 제외한다.
 * 이 필터는 본문과 쿠키를 읽지 않고 최종 결과와 응답 상태, 소요 시간만 기록한다.
 * 클라이언트 IP는 공유 해석기로 정하고 감사 로그를 남기는 동안 MDC에 넣는다.
 * 보안 체인보다 먼저 실행해 입력 오류와 시도 횟수 제한으로 끝나는 요청도 기록한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AdminLoginAuditFilter extends OncePerRequestFilter {
    private final MeterRegistry meters;
    private static final String LOGIN_PATH = "/v1/admin/auth/tokens";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !LOGIN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String previousClientIp = MDC.get("clientIp");
        MDC.put("clientIp", ClientIpResolver.resolve(request));
        try (AdminLoginAuditContext context = AdminLoginAuditContext.open()) {
            boolean completed = false;
            try {
                chain.doFilter(request, response);
                completed = true;
            } finally {
                int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                if (!context.handled()) {
                    recordUnhandled(status, started);
                }
            }
        } finally {
            if (previousClientIp == null) {
                MDC.remove("clientIp");
            } else {
                MDC.put("clientIp", previousClientIp);
            }
        }
    }

    private void recordUnhandled(int status, long started) {
        if (status == 429) {
            meters.counter("admin.login.rejected", "reason", "RATE_LIMIT").increment();
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (status >= 500) {
            log.error("event=ADMIN_LOGIN_FAILED httpStatus={} durationMs={}", status, durationMs);
        } else if (status == HttpServletResponse.SC_OK) {
            log.info("event=ADMIN_LOGIN_SUCCESS httpStatus={} durationMs={}", status, durationMs);
        } else {
            log.info("event=ADMIN_LOGIN_FAILED httpStatus={} durationMs={}", status, durationMs);
        }
    }
}
