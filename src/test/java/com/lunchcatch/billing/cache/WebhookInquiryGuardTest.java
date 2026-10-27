package com.lunchcatch.billing.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WebhookInquiryGuardTest {

    private final AtomicLong nanos = new AtomicLong();
    private final WebhookInquiryGuard guard = new WebhookInquiryGuard(nanos::get);

    @Test
    void 같은_주문은_창_안에서_한_번만_통과한다() {
        assertThat(guard.tryAcquire("ord_1")).isTrue();
        assertThat(guard.tryAcquire("ord_1")).isFalse();
    }

    @Test
    void 다른_주문은_서로_막지_않는다() {
        assertThat(guard.tryAcquire("ord_1")).isTrue();
        assertThat(guard.tryAcquire("ord_2")).isTrue();
    }

    @Test
    void 창이_지나면_다시_통과한다() {
        guard.tryAcquire("ord_1");

        nanos.addAndGet(Duration.ofSeconds(11).toNanos());

        assertThat(guard.tryAcquire("ord_1")).isTrue();
    }

    @Test
    void 기본_생성자도_쓸_수_있다() {
        assertThat(new WebhookInquiryGuard().tryAcquire("ord_1")).isTrue();
    }
}
