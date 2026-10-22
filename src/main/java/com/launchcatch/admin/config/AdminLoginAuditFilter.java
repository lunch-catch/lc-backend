package com.launchcatch.admin.config;

import com.launchcatch.global.http.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/*
 * 관리자 로그인은 비밀번호를 보호하기 위해 일반 본문 로깅에서 제외한다.
 * 이 필터는 본문과 쿠키를 읽지 않고 로그인 시각, IP와 최종 성공 여부만 기록한다.
 * 보안 체인보다 먼저 실행해 입력 오류와 시도 횟수 제한으로 끝나는 요청도 기록한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AdminLoginAuditFilter extends OncePerRequestFilter {
    private static final String LOGIN_PATH = "/v1/admin/auth/tokens";
    private final Clock clock;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !LOGIN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ZonedDateTime occurredAt = ZonedDateTime.now(clock);
        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            int status = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            boolean success = completed && status == HttpServletResponse.SC_OK;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            String clientIp = ClientIpResolver.resolve(request);
            String message = "event=ADMIN_LOGIN occurredAt={} clientIp={} success={} status={} durationMs={}";
            if (status >= 500) {
                log.error(message, occurredAt, clientIp, success, status, durationMs);
            } else if (!success) {
                log.warn(message, occurredAt, clientIp, false, status, durationMs);
            } else {
                log.info(message, occurredAt, clientIp, true, status, durationMs);
            }
        }
    }
}
