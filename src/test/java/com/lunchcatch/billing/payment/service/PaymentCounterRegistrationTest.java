package com.lunchcatch.billing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

// 기동 때 결과 라벨마다 카운터가 0 으로 있어야 경보가 첫 사건의 증가를 센다(#1027)
class PaymentCounterRegistrationTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private void assertZero(String name, String tagKey, List<String> values) {
        for (String value : values) {
            assertThat(registry.get(name).tag(tagKey, value).counter().count()).as(name + " " + value).isZero();
        }
    }

    @Test
    void 결제_승인_결과마다_카운터를_0으로_만든다() {
        new PaymentConfirmationService(null, null, null, registry).registerCounters();

        assertZero(PaymentConfirmationService.CONFIRM_METRIC, "result",
                List.of("approved", "unknown", "failed", "not_sent"));
    }

    @Test
    void 웹훅_결과마다_카운터를_0으로_만든다() {
        new PaymentWebhookService(null, null, null, null, registry).registerCounters();

        assertZero(PaymentWebhookService.WEBHOOK_METRIC, "result",
                List.of("approved", "cancelled", "ignored", "throttled", "failed"));
    }

    @Test
    void 재조회_판정과_오류마다_카운터를_0으로_만든다() {
        new PaymentRecheckService(null, null, null, null, registry).registerCounters();

        assertZero(PaymentRecheckService.RECHECK_METRIC, "result",
                List.of("approve", "fail", "cancel", "miss", "error"));
    }

    @Test
    void 재조회_소진_카운터를_0으로_만든다() {
        new PaymentResultService(null, null, null, null, null, null, registry, null).registerCounters();

        assertThat(registry.get("billing.payment.recheck.exhausted").counter().count()).isZero();
    }
}
