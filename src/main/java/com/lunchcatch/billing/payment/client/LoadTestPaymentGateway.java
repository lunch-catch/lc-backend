package com.lunchcatch.billing.payment.client;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/*
 * 부하 시험(loadtest 프로필)에서 토스 대신 쓰는 결제 목이다 (lc-infra #11).
 * 팀원 계정의 부하 시험 환경이 이 프로필로 뜬다. 외부 서비스에 부하를 걸지 않고 우리 쪽 처리만 잰다.
 *
 * 승인은 항상 성공하고, 토스와 비슷하게 정해진 시간(loadtest.payment.latency)만큼 기다린 뒤 돌려준다.
 * 조회는 이 목이 승인한 결제면 승인으로, 아니면 없음으로 답한다.
 *
 * 승인 기록은 Valkey 에 둔다(#1123). 승인은 앱 서버의 목이 받고 재조회 워커는 배치 서버에서 돌아서,
 * JVM 메모리에 두면 워커가 묻는 목은 그 승인을 모르고 없음으로 답한다. 실제 토스는 어느 서버에서 물어도 같은 답을 준다.
 *
 * 실제 결제 환경에 잘못 켜지면 돈을 받지 않고 포인트를 충전하게 된다. 라이브 키가 보이면 기동을 막는다.
 */
@Slf4j
@Component
@Profile("loadtest")
public class LoadTestPaymentGateway implements PaymentGateway {

    static final String LIVE_KEY_PREFIX = "live_";

    private static final String PAYMENT_KEY_PREFIX = "loadtest:payment:key:";
    private static final String ORDER_ID_PREFIX = "loadtest:payment:order:";

    // 부하 시험이 끝난 다음 날 01:30 대사까지 조회될 수 있어 이틀 둔다
    private static final Duration RECORD_TTL = Duration.ofDays(2);

    private final Clock clock;
    private final Duration latency;
    private final StringRedisTemplate redis;

    // 지연을 문자열로 받아 직접 읽어 변환기가 없는 컨텍스트에서도 "200ms" 같은 값이 같게 읽히게 한다
    @Autowired
    public LoadTestPaymentGateway(Clock clock,
            @Value("${loadtest.payment.latency:200ms}") String latency,
            @Value("${billing.toss.secret-key:}") String secretKey,
            StringRedisTemplate redis) {
        this(clock, DurationStyle.detectAndParse(latency), secretKey, redis);
    }

    LoadTestPaymentGateway(Clock clock, Duration latency, String secretKey, StringRedisTemplate redis) {
        if (secretKey.startsWith(LIVE_KEY_PREFIX)) {
            throw new IllegalStateException("토스 라이브 키가 있는 환경에서 결제 목(loadtest 프로필)을 켤 수 없습니다.");
        }
        this.clock = clock;
        this.latency = latency;
        this.redis = redis;
        log.warn("event=LOADTEST_PAYMENT_MOCK_ENABLED latencyMs={} 결제 목이 켜졌다. 토스를 부르지 않는다",
                latency.toMillis());
    }

    @Override
    public PaymentGatewayApproval confirm(String paymentKey, String orderId, long amount) {
        pause();
        PaymentGatewayApproval approval =
                new PaymentGatewayApproval(paymentKey, orderId, amount, LocalDateTime.now(clock));
        record(approval);
        return approval;
    }

    @Override
    public PaymentGatewayInquiryResult inquireByOrderId(String orderId) {
        return toInquiry(read(ORDER_ID_PREFIX + orderId));
    }

    @Override
    public PaymentGatewayInquiryResult inquireByPaymentKey(String paymentKey) {
        return toInquiry(read(PAYMENT_KEY_PREFIX + paymentKey));
    }

    /*
     * 기록을 쓰지 못해도 승인은 돌려준다. 던지면 Valkey 장애가 결제 실패와 UNKNOWN 으로 번져
     * 부하 시험의 승인 수치가 Valkey 상태에 따라 흔들린다. 그 결제는 나중에 조회하면 없음으로 보인다.
     */
    private void record(PaymentGatewayApproval approval) {
        String value = String.join(" ", approval.paymentKey(), approval.orderId(),
                String.valueOf(approval.amount()), approval.approvedAt().toString());
        try {
            redis.opsForValue().set(PAYMENT_KEY_PREFIX + approval.paymentKey(), value, RECORD_TTL);
            redis.opsForValue().set(ORDER_ID_PREFIX + approval.orderId(), value, RECORD_TTL);
        } catch (DataAccessException e) {
            log.warn("event=LOADTEST_PAYMENT_MOCK_RECORD_FAILED orderId={}", approval.orderId(), e);
        }
    }

    /*
     * 기록을 읽지 못하면 없음이 아니라 응답 불명으로 던진다. 없음으로 답하면 실제로 승인한 결제를
     * 워커가 결제대행사에 없다고 판정한다. 불명이면 워커가 판정을 보류하고 다음에 다시 묻는다.
     */
    private PaymentGatewayApproval read(String key) {
        String value;
        try {
            value = redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            throw new PgUnknownException("부하 시험 결제 목이 승인 기록을 읽지 못했습니다.", e);
        }
        if (value == null) {
            return null;
        }
        // 결제 키와 주문 번호는 영문, 숫자, _, - 만 쓰므로 공백으로 나눈다
        String[] fields = value.split(" ");
        return new PaymentGatewayApproval(fields[0], fields[1], Long.parseLong(fields[2]),
                LocalDateTime.parse(fields[3]));
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
}
