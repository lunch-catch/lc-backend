package com.launchcatch.coupon.service;

import static com.launchcatch.coupon.service.CouponBatchFixture.DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;

import com.launchcatch.coupon.contract.CouponExpiredEvent;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.repository.CouponBatchRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.ops.contract.BatchAttemptGuard;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CouponExpirationServiceTest {
    private final CouponBatchRepository batches = mock(CouponBatchRepository.class);
    private final CouponEventRepository events = mock(CouponEventRepository.class);
    private final MemberCouponRepository coupons = mock(MemberCouponRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final MemberCoupon coupon = CouponBatchFixture.coupon();

    @Test
    void 만료_전이와_이벤트를_함께_반영하고_이벤트는_사용_종료_시각을_갖는다() {
        assertThat(service().expire(DATE, 1)).isEqualTo(1);
        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.EXPIRED);
        ArgumentCaptor<CouponExpiredEvent> published = ArgumentCaptor.forClass(CouponExpiredEvent.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue().eventId()).isEqualTo("coupon-expired:2");
        assertThat(published.getValue().occurredAt()).isEqualTo(DATE.atTime(15, 0));
    }

    @Test
    void 이미_발행한_만료는_반복하지_않는다() {
        coupon.changeToExpired(DATE.atTime(15, 0));
        assertThat(service().expire(DATE, 2)).isZero();
        verifyNoInteractions(publisher);
    }

    private CouponExpirationService service() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(batches.expiringIds(DATE, DATE.atTime(16, 0), 0)).thenReturn(List.of(2L));
        when(coupons.findByIdForUpdate(2L)).thenReturn(Optional.of(coupon));
        lenient().when(events.findById(1L)).thenReturn(Optional.of(CouponBatchFixture.event()));
        ZoneId zone = ZoneId.of("Asia/Seoul");
        return new CouponExpirationService(batches, events, coupons, mock(BatchAttemptGuard.class), publisher,
                Clock.fixed(DATE.atTime(16, 0).atZone(zone).toInstant(), zone), manager);
    }
}
