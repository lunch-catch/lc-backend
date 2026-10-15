package com.launchcatch.billing.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import org.springframework.stereotype.Component;

/*
 * 웹훅이 같은 주문으로 결제대행사 조회를 연달아 일으키지 못하게 막는다.
 *
 * 결제 상태 웹훅은 서명으로 검증할 수 없어서 주문 번호를 아는 누구나 보낼 수 있다. 요청마다 조회하면
 * 승인과 재조회가 쓰는 연결과 서킷 브레이커를 같이 써서 정상 결제를 밀어낼 수 있다.
 * 서버 한 대 안의 메모리로 막는다. 서버가 여러 대면 대수만큼 늘어나지만 부하를 줄이는 목적에는 충분하다.
 * 주문 번호만 키로 쓰고 결제 키 같은 요청 값은 담지 않는다.
 */
@Component
public class WebhookInquiryGuard {

    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final long MAX_ORDERS = 10_000;

    private final Cache<String, Boolean> recent;

    public WebhookInquiryGuard() {
        this(Ticker.systemTicker());
    }

    WebhookInquiryGuard(Ticker ticker) {
        this.recent = Caffeine.newBuilder()
                .expireAfterWrite(WINDOW)
                .maximumSize(MAX_ORDERS)
                .ticker(ticker)
                .build();
    }

    // 이 주문으로 조회해도 되면 true 이고 그 순간부터 창이 시작된다. 창 안의 두 번째부터는 false 다
    public boolean tryAcquire(String orderId) {
        return recent.asMap().putIfAbsent(orderId, Boolean.TRUE) == null;
    }
}
