package com.launchcatch.billing.client;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/*
 * 부하 시험(loadtest 프로필)에서 토스 대신 쓰는 결제 목이다 (lc-infra #11).
 * 팀원 계정의 부하 시험 환경이 이 프로필로 뜬다. 외부 서비스에 부하를 걸지 않고 우리 쪽 처리만 잰다.
 *
 * 승인은 항상 성공하고, 토스와 비슷하게 정해진 시간(loadtest.payment.latency)만큼 기다린 뒤 돌려준다.
 * 조회는 이 목이 승인한 결제면 승인으로, 아니면 없음으로 답한다. 기억은 최근 승인만 남긴다.
 *
 * 실제 결제 환경에 잘못 켜지면 돈을 받지 않고 포인트를 충전하게 된다. 라이브 키가 보이면 기동을 막는다.
 */
@Slf4j
@Component
@Profile("loadtest")
public class LoadTestPaymentGateway implements PaymentGateway {

    static final String LIVE_KEY_PREFIX = "live_";

    // 부하 시험 몇 시간 동안 쌓여도 메모리를 넘기지 않게 최근 승인만 남긴다
    static final int REMEMBERED_APPROVALS = 100_000;

    private final Clock clock;
    private final Duration latency;
    private final Map<String, PaymentGatewayApproval> byOrderId = remembered();
    private final Map<String, PaymentGatewayApproval> byPaymentKey = remembered();

    // 지연을 문자열로 받아 직접 읽어 변환기가 없는 컨텍스트에서도 "200ms" 같은 값이 같게 읽히게 한다
    @Autowired
    public LoadTestPaymentGateway(Clock clock,
            @Value("${loadtest.payment.latency:200ms}") String latency,
            @Value("${billing.toss.secret-key:}") String secretKey) {
        this(clock, DurationStyle.detectAndParse(latency), secretKey);
    }

    LoadTestPaymentGateway(Clock clock, Duration latency, String secretKey) {
        if (secretKey.startsWith(LIVE_KEY_PREFIX)) {
            throw new IllegalStateException("토스 라이브 키가 있는 환경에서 결제 목(loadtest 프로필)을 켤 수 없습니다.");
        }
        this.clock = clock;
        this.latency = latency;
        log.warn("event=LOADTEST_PAYMENT_MOCK_ENABLED latencyMs={} 결제 목이 켜졌다. 토스를 부르지 않는다",
                latency.toMillis());
    }

    @Override
    public PaymentGatewayApproval confirm(String paymentKey, String orderId, long amount) {
        pause();
        PaymentGatewayApproval approval =
                new PaymentGatewayApproval(paymentKey, orderId, amount, LocalDateTime.now(clock));
        byOrderId.put(orderId, approval);
        byPaymentKey.put(paymentKey, approval);
        return approval;
    }

    @Override
    public PaymentGatewayInquiryResult inquireByOrderId(String orderId) {
        return toInquiry(byOrderId.get(orderId));
    }

    @Override
    public PaymentGatewayInquiryResult inquireByPaymentKey(String paymentKey) {
        return toInquiry(byPaymentKey.get(paymentKey));
    }

    private PaymentGatewayInquiryResult toInquiry(PaymentGatewayApproval approval) {
        if (approval == null) {
            return PaymentGatewayInquiryResult.notFound();
        }
        return new PaymentGatewayInquiryResult(PaymentGatewayInquiryResult.Status.APPROVED,
                approval.paymentKey(), approval.orderId(), approval.amount(), approval.approvedAt(), null, null);
    }

    private void pause() {
        if (latency.isZero() || latency.isNegative()) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, PaymentGatewayApproval> remembered() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PaymentGatewayApproval> eldest) {
                return size() > REMEMBERED_APPROVALS;
            }
        });
    }
}
