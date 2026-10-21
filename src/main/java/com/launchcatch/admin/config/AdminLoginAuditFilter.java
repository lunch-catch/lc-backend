package com.launchcatch.admin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/*
 * 관리자 로그인은 비밀번호를 보호하기 위해 일반 본문 로깅에서 제외한다.
 * 이 필터는 본문과 쿠키를 읽지 않고 최종 결과와 응답 상태, 소요 시간만 기록한다.
 * 보안 체인보다 먼저 실행해 입력 오류와 시도 횟수 제한으로 끝나는 요청도 기록한다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AdminLoginAuditFilter extends OncePerRequestFilter {
    private static final String LOGIN_PATH = "/v1/admin/auth/tokens";

    // 기존 생성자 호출과의 호환을 유지하며 로그 시각은 로깅 프레임워크에 맡긴다.
    public AdminLoginAuditFilter(Clock clock) { }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !LOGIN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            boolean success = completed && status == HttpServletResponse.SC_OK;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (status >= 500) {
                log.error("event=ADMIN_LOGIN_FAILED httpStatus={} durationMs={}", status, durationMs);
            } else if (!success) {
                log.info("event=ADMIN_LOGIN_FAILED httpStatus={} durationMs={}", status, durationMs);
            } else {
                log.info("event=ADMIN_LOGIN_SUCCESS httpStatus={} durationMs={}", status, durationMs);
            }
        }
    }
}
