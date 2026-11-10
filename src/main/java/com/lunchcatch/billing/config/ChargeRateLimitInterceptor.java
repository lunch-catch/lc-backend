package com.lunchcatch.billing.config;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.billing.cache.ChargeRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/*
 * 충전 주문과 승인 확정에 점주별 상한을 건다(#1122).
 *
 * OwnerActiveInterceptor 보다 먼저 돈다. 그 인터셉터는 점주 상태를 DB 에서 읽으므로, 뒤에 두면 상한을 넘은 요청도 커넥션을 받는다.
 * 같은 경로의 GET(결제 내역)은 세지 않는다. 경로 패턴이 메서드를 가리지 못해 여기서 POST 만 고른다.
 * 점주 주체가 없으면 세지 않고 넘긴다. 뒤의 OwnerActiveInterceptor 가 막는다.
 */
@Component
@RequiredArgsConstructor
public class ChargeRateLimitInterceptor implements HandlerInterceptor {

    static final String[] PATHS = {"/v1/owner/payments", "/v1/owner/payments/*:confirm"};

    private final ChargeRateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails owner) {
            rateLimiter.checkLimit(owner.getId());
        }
        return true;
    }
}
