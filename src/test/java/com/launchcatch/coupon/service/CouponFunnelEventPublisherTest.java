package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.contract.CouponExpiredEvent;
import com.launchcatch.coupon.contract.CouponIssueOpenedEvent;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import com.launchcatch.coupon.contract.CouponRedeemedEvent;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.MemberCoupon;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CouponFunnelEventPublisherTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 11, 0);
    private final List<Object> received = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CouponFunnelEventPublisher publisher = new CouponFunnelEventPublisher(received::add, meters);
    private CouponEvent event;
    private MemberCoupon coupon;

    @BeforeEach
    void setUp() {
        event = mock(CouponEvent.class);
        when(event.getId()).thenReturn(81L);
        when(event.getCampaignId()).thenReturn(91L);
        when(event.getStoreId()).thenReturn(101L);
        coupon = MemberCoupon.issue(17L, 81L, UUID.randomUUID(), NOW, NOW.plusHours(1));
        ReflectionTestUtils.setField(coupon, "id", 1001L);
    }

    @Test
    void 네_이벤트는_커밋한_뒤에만_공개하고_같은_쿠폰과_회차를_가리킨다() {
        inTransaction(true, () -> {
            publishAll();
            assertThat(received).isEmpty();
        });

        assertThat(received).containsExactly(
                new CouponIssueOpenedEvent("coupon-open:81", NOW, 91, 101, 81),
                new CouponIssuedEvent("coupon-issued:1001", NOW, 17, 91, 101, 81, 1001),
                new CouponRedeemedEvent("coupon-redeemed:1001", NOW, 17, 91, 101, 81, 1001),
                new CouponExpiredEvent("coupon-expired:1001", NOW.plusHours(1), 17, 91, 101, 81, 1001));
    }

    @Test
    void 롤백하면_네_이벤트를_발행하지_않는다() {
        inTransaction(false, this::publishAll);
        assertThat(received).isEmpty();
    }

    @Test
    void 같은_동작을_다시_처리해도_eventId와_발생_시각을_유지한다() {
        inTransaction(true, this::publishAll);
        List<Object> first = List.copyOf(received);
        inTransaction(true, this::publishAll);
        assertThat(received.subList(4, 8)).isEqualTo(first);
    }

    @Test
    void 커밋_후_리스너가_실패해도_업무_성공을_유지하고_유실을_센다() {
        ApplicationEventPublisher broken = ignored -> { throw new IllegalStateException("분석 장애"); };
        CouponFunnelEventPublisher failing = new CouponFunnelEventPublisher(broken, meters);

        inTransaction(true, () -> failing.issued(event, coupon, NOW));

        assertThat(meters.get("coupon.funnel.events.dropped").tag("type", "CouponIssuedEvent")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void 트랜잭션_없이_발행하면_거절한다() {
        assertThatThrownBy(() -> publisher.issued(event, coupon, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(received).isEmpty();
    }

    private void publishAll() {
        publisher.opened(event, NOW);
        publisher.issued(event, coupon, NOW);
        publisher.redeemed(event, coupon, NOW);
        publisher.expired(event, coupon);
    }

    private void inTransaction(boolean commit, Runnable work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            work.run();
            List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
            if (commit) {
                callbacks.forEach(TransactionSynchronization::afterCommit);
            }
            callbacks.forEach(callback -> callback.afterCompletion(commit
                    ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
