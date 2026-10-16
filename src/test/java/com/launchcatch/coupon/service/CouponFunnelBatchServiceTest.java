package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class CouponFunnelBatchServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 16);
    private static final LocalDateTime END = DATE.atTime(13, 0);
    @Mock CouponEventRepository events;
    @Mock MemberCouponRepository coupons;
    @Mock CouponFunnelEventPublisher publisher;
    @Mock BatchExecutionService executions;
    @Mock RetryBackoff backoff;
    private CouponFunnelBatchService service;
    private CouponEvent event;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        lenient().when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new CouponFunnelBatchService(events, coupons, publisher, executions, backoff, manager);
        event = BeanUtils.instantiateClass(CouponEvent.class);
        ReflectionTestUtils.setField(event, "id", 81L);
        ReflectionTestUtils.setField(event, "campaignId", 91L);
        ReflectionTestUtils.setField(event, "storeId", 101L);
        ReflectionTestUtils.setField(event, "businessDate", DATE);
        ReflectionTestUtils.setField(event, "status", CouponEventStatus.ACTIVE);
        ReflectionTestUtils.setField(event, "usableEndAt", END);
    }

    @Test
    void 오픈_회차를_캠페인별로_점유하고_11시에_발행한다() {
        when(events.findByBusinessDateAndStatusAndIdGreaterThanOrderByIdAsc(
                DATE, CouponEventStatus.ACTIVE, 0, PageRequest.of(0, 500))).thenReturn(List.of(event));
        when(executions.begin("COUPON_OPEN_91", DATE)).thenReturn(StepOutcome.STARTED);
        when(events.findByIdForUpdate(81L)).thenReturn(Optional.of(event));

        service.open(DATE);

        verify(publisher).opened(event, DATE.atTime(11, 0));
        verify(executions).succeed("COUPON_OPEN_91", DATE);
    }

    @Test
    void 이미_끝난_회차나_다른_서버가_점유한_회차는_발행하지_않는다() {
        when(events.findByBusinessDateAndStatusAndIdGreaterThanOrderByIdAsc(
                DATE, CouponEventStatus.ACTIVE, 0, PageRequest.of(0, 500))).thenReturn(List.of(event));
        when(executions.begin("COUPON_OPEN_91", DATE))
                .thenReturn(StepOutcome.ALREADY_DONE, StepOutcome.BLOCKED);

        service.open(DATE);
        service.open(DATE);

        verify(publisher, never()).opened(any(), any());
    }

    @Test
    void 잠금_대기_중_중지된_회차는_오픈을_알리지_않는다() {
        when(events.findByBusinessDateAndStatusAndIdGreaterThanOrderByIdAsc(
                DATE, CouponEventStatus.ACTIVE, 0, PageRequest.of(0, 500))).thenReturn(List.of(event));
        when(executions.begin("COUPON_OPEN_91", DATE)).thenReturn(StepOutcome.STARTED);
        when(events.findByIdForUpdate(81L)).thenReturn(Optional.of(event));
        ReflectionTestUtils.setField(event, "status", CouponEventStatus.PAUSED);

        service.open(DATE);

        verify(publisher, never()).opened(any(), any());
    }

    @Test
    void 임시_상한_500개씩_만료하고_조회가_먼저_만료한_쿠폰도_같이_알린다() {
        when(events.findByUsableEndAtLessThanEqualAndIdGreaterThanOrderByIdAsc(
                END, 0, PageRequest.of(0, 500))).thenReturn(List.of(event));
        when(executions.begin("COUPON_EXPIRE_91", DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.renew("COUPON_EXPIRE_91", DATE)).thenReturn(true);
        List<MemberCoupon> first = java.util.stream.LongStream.rangeClosed(1, 500).mapToObj(this::coupon).toList();
        MemberCoupon alreadyExpired = coupon(501);
        alreadyExpired.changeToExpired(END);
        when(coupons.findExpirationPage(eq(81L), eq(END), eq(0L), any())).thenReturn(first);
        when(coupons.findExpirationPage(eq(81L), eq(END), eq(500L), any())).thenReturn(List.of(alreadyExpired));

        service.expire(END);

        assertThat(first).allMatch(coupon -> coupon.getStatus() == MemberCouponStatus.EXPIRED);
        verify(publisher, times(501)).expired(eq(event), any());
        verify(coupons).findExpirationPage(81L, END, 0, PageRequest.of(0, 500));
        verify(coupons).findExpirationPage(81L, END, 500, PageRequest.of(0, 500));
        verify(executions).succeed("COUPON_EXPIRE_91", DATE);
    }

    @Test
    void 일시적_DB_장애는_공통_예산과_대기_후_다시_처리한다() {
        expiredEvent();
        when(coupons.findExpirationPage(eq(81L), eq(END), anyLong(), any()))
                .thenThrow(new DataAccessResourceFailureException("DB 연결 실패"))
                .thenReturn(List.of(coupon(1)));
        when(executions.recordRetry(eq("COUPON_EXPIRE_91"), eq(DATE), any())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);
        when(executions.renew("COUPON_EXPIRE_91", DATE)).thenReturn(true);

        service.expire(END);

        verify(publisher).expired(eq(event), any());
        verify(executions).succeed("COUPON_EXPIRE_91", DATE);
    }

    @Test
    void 재시도_중_점유를_잃으면_다음_페이지를_처리하지_않는다() {
        expiredEvent();
        when(executions.renew("COUPON_EXPIRE_91", DATE)).thenReturn(true, false);
        when(coupons.findExpirationPage(eq(81L), eq(END), anyLong(), any()))
                .thenThrow(new DataAccessResourceFailureException("DB 연결 실패"));
        when(executions.recordRetry(eq("COUPON_EXPIRE_91"), eq(DATE), any())).thenReturn(true);
        when(backoff.pause(1)).thenReturn(true);

        service.expire(END);

        verify(publisher, never()).expired(any(), any());
        verify(executions, never()).succeed(any(), any());
    }

    @Test
    void 정합성_오류는_재시도하지_않고_실패로_남긴다() {
        expiredEvent();
        when(coupons.findExpirationPage(eq(81L), eq(END), anyLong(), any())).thenReturn(List.of(coupon(1)));
        doThrow(new IllegalStateException("잘못된 참조")).when(publisher).expired(any(), any());

        service.expire(END);

        verify(executions).fail(eq("COUPON_EXPIRE_91"), eq(DATE), any());
        verify(executions, never()).recordRetry(any(), any(), any());
    }

    private void expiredEvent() {
        when(events.findByUsableEndAtLessThanEqualAndIdGreaterThanOrderByIdAsc(
                END, 0, PageRequest.of(0, 500))).thenReturn(List.of(event));
        when(executions.begin("COUPON_EXPIRE_91", DATE)).thenReturn(StepOutcome.STARTED);
        when(executions.renew("COUPON_EXPIRE_91", DATE)).thenReturn(true);
    }

    private MemberCoupon coupon(long id) {
        MemberCoupon coupon = MemberCoupon.issue(id, 81L, UUID.randomUUID(), END.minusHours(2), END);
        ReflectionTestUtils.setField(coupon, "id", id);
        return coupon;
    }
}
