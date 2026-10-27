package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

// 기동 때 결과 라벨마다 카운터가 0 으로 있어야 경보가 첫 사건의 증가를 센다(#1027)
class BillingCounterRegistrationTest {

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

    @Test
    void PG_대사_결과마다_카운터를_0으로_만든다() {
        new PgChargeReconciliationService(null, null, null, null, null, null, registry, null).registerCounters();

        assertZero(PgChargeReconciliationService.RECON_METRIC, "result",
                List.of("mismatch", "recovered", "inquiry_failed", "error"));
    }

    @Test
    void 노출_차감의_되돌리기_계획없음_묶음실패_묶음대체_카운터를_0으로_만든다() {
        new PointDeductorImpl(null, null, null, null, null, null, null, null, registry, null, null, null)
                .registerCounters();

        assertZero(PointDeductorImpl.UNDO_METRIC, "result", List.of("ok", "failed"));
        assertThat(registry.get(PointDeductorImpl.NO_PLAN_METRIC).counter().count()).isZero();
        assertZero(PointDeductorImpl.BATCH_FAILED_METRIC, "reason", List.of("owner_group", "unknown_campaign"));
        assertThat(registry.get(PointDeductorImpl.BATCH_FALLBACK_METRIC).counter().count()).isZero();
    }

    @Test
    void 해시_재구성_카운터를_0으로_만든다() {
        new SpendHashRebuildService(null, null, null, registry).registerCounters();

        assertThat(registry.get(SpendHashRebuildService.REBUILD_METRIC).counter().count()).isZero();
    }
}
