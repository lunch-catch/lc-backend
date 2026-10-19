package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.provider.CsvSource;
import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.entity.CouponInventory;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponInventoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class CouponIssuanceServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private final CouponDailyLimitRepository counters = mock(CouponDailyLimitRepository.class);
    private final CouponEventRepository events = mock(CouponEventRepository.class);
    private final CouponInventoryRepository inventories = mock(CouponInventoryRepository.class);
    private final MemberCouponRepository coupons = mock(MemberCouponRepository.class);
    private final MemberQueryService members = mock(MemberQueryService.class);
    private final WishlistQueryService wishes = mock(WishlistQueryService.class);
    @Mock ApplicationEventPublisher eventPublisher;
    private CouponEvent event;
    private CouponInventory inventory;
    private CouponIssuanceService service;

    @BeforeEach
    void setUp() {
        when(wishes.isWished(17L, 91L)).thenReturn(true);
        event = BeanUtils.instantiateClass(CouponEvent.class);
        ReflectionTestUtils.setField(event, "id", 81L);
        ReflectionTestUtils.setField(event, "campaignId", 91L);
        ReflectionTestUtils.setField(event, "storeId", 10L);
        ReflectionTestUtils.setField(event, "businessDate", TODAY);
        ReflectionTestUtils.setField(event, "status", CouponEventStatus.ACTIVE);
        ReflectionTestUtils.setField(event, "usableStartAt", TODAY.atTime(11, 0));
        ReflectionTestUtils.setField(event, "usableEndAt", TODAY.atTime(14, 0));
        inventory = BeanUtils.instantiateClass(CouponInventory.class);
        ReflectionTestUtils.setField(inventory, "status", CouponInventoryStatus.AVAILABLE);
        ReflectionTestUtils.setField(inventory, "couponCode", UUID.randomUUID());
        when(counters.increaseForIssue(any(), any(), any(), anyInt(), any())).thenReturn(1);
        when(events.findById(81L)).thenReturn(Optional.of(event));
        when(members.findCurrentStatus(17L)).thenReturn(Optional.of(MemberStatus.ACTIVE));
        when(inventories.findFirstByCouponEventIdAndStatusOrderBySequenceNoAsc(81L, CouponInventoryStatus.AVAILABLE))
                .thenReturn(Optional.of(inventory));
        when(coupons.saveAndFlush(any())).thenAnswer(invocation -> {
            MemberCoupon coupon = invocation.getArgument(0);
            ReflectionTestUtils.setField(coupon, "id", 1001L);
            ReflectionTestUtils.setField(coupon, "createdAt", TODAY.atTime(12, 0));
            return coupon;
        });
        service = serviceAt(TODAY.atTime(12, 0));
    }

    @Test
    void 발급한_재고의_코드와_이벤트_사용_시간을_저장하고_저장된_발급_시각을_반환한다() {
        var result = service.issue(17L, 81L).response();
        assertThat(result.memberCouponId()).isEqualTo(1001L);
        verify(eventPublisher).publishEvent(new CouponIssuedEvent(
                "coupon-issued:1001", TODAY.atTime(12, 0), 17, 91, 10, 81, 1001));
        assertThat(result.issueTime().toString()).isEqualTo("2026-10-10T12:00+09:00");
        verify(counters).increaseForIssue(17L, TODAY, 81L, 3, TODAY.atTime(12, 0));
        verify(coupons, never()).existsByCouponEventIdAndMemberId(81L, 17L);
        assertThat(inventory.getStatus()).isEqualTo(CouponInventoryStatus.ISSUED);
        var saved = org.mockito.ArgumentCaptor.forClass(MemberCoupon.class);
        verify(coupons).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCouponCode()).isEqualTo(inventory.getCouponCode());
        assertThat(saved.getValue().getUsableEndAt()).isEqualTo(event.getUsableEndAt());
        assertThat(saved.getValue().getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
    }

    @Test
    void 이벤트가_없으면_찾을_수_없다는_오류로_거절한다() {
        when(events.findById(81L)).thenReturn(Optional.empty());
        expect(CouponErrorCode.COUPON_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10:59:59", "14:00:00", "23:59:59"})
    void 발급_시간_밖이면_거절한다(String time) {
        service = serviceAt(TODAY.atTime(LocalTime.parse(time)));
        expect(CouponErrorCode.COUPON_NOT_AVAILABLE);
    }

    @Test
    void 시작_시각은_포함한다() {
        var result = serviceAt(TODAY.atTime(11, 0)).issue(17L, 81L);

        assertThat(result.response().memberCouponId()).isEqualTo(1001L);
        assertThat(inventory.getStatus()).isEqualTo(CouponInventoryStatus.ISSUED);
    }

    @Test
    void 중지된_이벤트와_다른_영업일의_이벤트는_거절한다() {
        ReflectionTestUtils.setField(event, "status", CouponEventStatus.PAUSED);
        expect(CouponErrorCode.COUPON_NOT_AVAILABLE);
        ReflectionTestUtils.setField(event, "status", CouponEventStatus.ACTIVE);
        ReflectionTestUtils.setField(event, "businessDate", TODAY.minusDays(1));
        expect(CouponErrorCode.COUPON_NOT_AVAILABLE);
    }

    @Test
    void 없거나_정지된_회원은_거절한다() {
        when(members.findCurrentStatus(17L)).thenReturn(Optional.empty());
        expect(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
        when(members.findCurrentStatus(17L)).thenReturn(Optional.of(MemberStatus.SUSPENDED));
        expect(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
    }

    @Test
    void 기존_발급과_일일_한도와_재고_소진을_구분한다() {
        when(counters.increaseForIssue(any(), any(), any(), anyInt(), any())).thenReturn(0);
        when(coupons.existsByCouponEventIdAndMemberId(81L, 17L)).thenReturn(true);
        expect(CouponErrorCode.EVENT_ALREADY_ISSUED);
        when(coupons.existsByCouponEventIdAndMemberId(81L, 17L)).thenReturn(false);
        expect(CouponErrorCode.DAILY_LIMIT_REACHED);
        when(counters.increaseForIssue(any(), any(), any(), anyInt(), any())).thenReturn(1);
        when(inventories.findFirstByCouponEventIdAndStatusOrderBySequenceNoAsc(81L, CouponInventoryStatus.AVAILABLE))
                .thenReturn(Optional.empty());
        expect(CouponErrorCode.INVENTORY_EXHAUSTED);
    }

    @Test
    void DB_조회_실패를_그대로_전달한다() {
        var failure = new QueryTimeoutException("lock timeout");
        when(events.findById(81L)).thenThrow(failure);
        assertThatThrownBy(() -> service.issue(17L, 81L)).isSameAs(failure);
    }

    @Test
    void 커밋_실패는_발급_성공으로_반환하지_않는다() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(definition.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
            return new SimpleTransactionStatus();
        });
        var failure = new PessimisticLockingFailureException("commit failed");
        doThrow(failure).when(manager).commit(any());
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(interceptor);
        CouponIssuanceService transactionalService = (CouponIssuanceService) proxy.getProxy();

        assertThatThrownBy(() -> transactionalService.issue(17L, 81L)).isSameAs(failure);
    }

    @Test
    void 재고_잠금_대기_중_발급_시간이_끝나도_발급한다() {
        Clock changing = mock(Clock.class);
        Clock beforeEnd = Clock.fixed(TODAY.atTime(13, 59, 59).atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        Clock end = Clock.fixed(TODAY.atTime(14, 0).atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        var currentTime = new AtomicReference<>(beforeEnd);
        when(changing.withZone(ClockConfig.ZONE)).thenAnswer(invocation -> currentTime.get());
        when(inventories.findFirstByCouponEventIdAndStatusOrderBySequenceNoAsc(81L, CouponInventoryStatus.AVAILABLE))
                .thenAnswer(invocation -> {
                    currentTime.set(end);
                    return Optional.of(inventory);
                });
        service = serviceWith(changing);

        var result = service.issue(17L, 81L);

        assertThat(result.response().memberCouponId()).isEqualTo(1001L);
        assertThat(inventory.getStatus()).isEqualTo(CouponInventoryStatus.ISSUED);
    }

    private void expect(CouponErrorCode code) {
        assertThatThrownBy(() -> service.issue(17L, 81L)).isInstanceOfSatisfying(CouponException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private CouponIssuanceService serviceAt(LocalDateTime now) {
        return serviceWith(Clock.fixed(now.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE));
    }

    private CouponIssuanceService serviceWith(Clock clock) {
        var service = new CouponIssuanceService(coupons, counters, members, events, inventories, wishes, clock, eventPublisher);
        ReflectionTestUtils.setField(service, "dailyLimit", 3);
        ReflectionTestUtils.setField(service, "issueStartTime", LocalTime.of(11, 0));
        ReflectionTestUtils.setField(service, "issueEndTime", LocalTime.of(14, 0));
        return service;
    }

    @Test
    void 찜하지_않았으면_발급을_거절하고_재고와_횟수를_소비하지_않는다() {
        when(wishes.isWished(17L, 91L)).thenReturn(false);
        expect(CouponErrorCode.CAMPAIGN_NOT_WISHED);
        verifyNoInteractions(counters);
        assertThat(inventory.getStatus()).isEqualTo(CouponInventoryStatus.AVAILABLE);
        verify(coupons, never()).saveAndFlush(any());
    }

    @Test
    void 찜_조회_장애를_찜_없음으로_바꾸지_않는다() {
        var failure = new QueryTimeoutException("wishlist unavailable");
        when(wishes.isWished(17L, 91L)).thenThrow(failure);
        assertThatThrownBy(() -> service.issue(17L, 81L)).isSameAs(failure);
    }

    @Test
    void 회원_상태_조회_장애를_없는_회원으로_바꾸지_않는다() {
        var failure = new QueryTimeoutException("member unavailable");
        when(members.findCurrentStatus(17L)).thenThrow(failure);

        assertThatThrownBy(() -> service.issue(17L, 81L)).isSameAs(failure);
        verifyNoInteractions(counters);
    }

    @Test
    void 카운터_증가_장애를_한도_초과로_바꾸지_않는다() {
        var failure = new QueryTimeoutException("counter unavailable");
        when(counters.increaseForIssue(any(), any(), any(), anyInt(), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.issue(17L, 81L)).isSameAs(failure);
        verifyNoInteractions(inventories);
    }

    @Test
    void 남은_재고가_있으면_소진_통보_대상을_만들지_않는다() {
        when(inventories.existsByCouponEventIdAndStatus(81L, CouponInventoryStatus.AVAILABLE)).thenReturn(true);
        assertThat(service.issue(17L, 81L).soldOut()).isNull();
    }

    @Test
    void 마지막_재고는_이벤트의_캠페인과_영업일과_발급_시각을_전달한다() {
        assertThat(service.issue(17L, 81L).soldOut())
                .isEqualTo(new CouponIssuanceService.SoldOut(91L, TODAY, TODAY.atTime(12, 0)));
    }

    @ParameterizedTest
    @CsvSource({"123456499, 123456000", "123456500, 123457000"})
    void 소진_시각을_DB의_마이크로초_반올림과_맞춘다(int nanos, int expectedNanos) {
        doAnswer(invocation -> {
            MemberCoupon coupon = invocation.getArgument(0);
            ReflectionTestUtils.setField(coupon, "id", 1001L);
            ReflectionTestUtils.setField(coupon, "createdAt", TODAY.atTime(12, 0).withNano(nanos));
            return coupon;
        }).when(coupons).saveAndFlush(any());
        assertThat(service.issue(17L, 81L).soldOut().soldOutAt())
                .isEqualTo(TODAY.atTime(12, 0).withNano(expectedNanos));
    }

    @Test
    void 소진_복구는_마지막_발급_시각을_사용한다() {
        when(events.findById(81L)).thenReturn(Optional.of(event));
        when(coupons.findLastIssuedAtByCouponEventId(81L)).thenReturn(Optional.of(TODAY.atTime(11, 30)));
        assertThat(service.findSoldOut(81L))
                .contains(new CouponIssuanceService.SoldOut(91L, TODAY, TODAY.atTime(11, 30)));
    }

    @Test
    void 이벤트가_없으면_복구할_소진도_없다() {
        assertThat(service.findSoldOut(81L)).isEmpty();
    }

    @Test
    void 발급_기록이_없으면_소진_시각을_만들지_않는다() {
        when(events.findById(81L)).thenReturn(Optional.of(event));
        assertThat(service.findSoldOut(81L)).isEmpty();
    }
}
