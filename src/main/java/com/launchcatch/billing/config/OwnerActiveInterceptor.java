package com.launchcatch.billing.config;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/*
 * 점주 결제, 포인트, 환불 API 를 ACTIVE 점주만 쓰게 한다.
 *
 * 서비스 일곱 곳에 같은 검사를 흩어 두지 않고 점주 경로가 모인 이 자리에서 한 번에 건다.
 * 경로 목록은 BillingSecurityConfig.OWNER_PATHS 를 그대로 쓴다. 보안 체인이 점주 경로를 하나 더하면
 * 상태 검사도 함께 걸린다.
 *
 * 캐시를 우회한 현재 상태를 읽는다. 정지된 점주가 캐시 TTL 동안 충전과 환불을 계속 하면 안 된다.
 * 점주가 없거나 주체를 알 수 없어도 같은 코드로 막는다. 허용은 ACTIVE 하나뿐이다.
 */
@Component
@RequiredArgsConstructor
public class OwnerActiveInterceptor implements HandlerInterceptor {

    private final OwnerStatusQuery ownerStatusQuery;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long ownerId = currentOwnerId();
        boolean active = ownerId != null
                && ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) == OwnerAccountStatus.ACTIVE;
        if (!active) {
            throw new BillingException(BillingErrorCode.OWNER_NOT_ACTIVE);
        }
        return true;
    }

    private Long currentOwnerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails owner) {
            return owner.getId();
        }
        return null;
    }
}
