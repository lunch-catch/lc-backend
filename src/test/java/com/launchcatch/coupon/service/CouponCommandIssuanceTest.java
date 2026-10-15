package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.CouponQrImageEncoder;
import com.launchcatch.coupon.entity.CouponDailyLimit;
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
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CouponCommandIssuanceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private final CouponDailyLimitRepository counters = mock(CouponDailyLimitRepository.class);
    private final CouponEventRepository events = mock(CouponEventRepository.class);
    private final CouponInventoryRepository inventories = mock(CouponInventoryRepository.class);
    private final MemberCouponRepository coupons = mock(MemberCouponRepository.class);
    private final MemberQueryService members = mock(MemberQueryService.class);
    private CouponDailyLimit counter;
    private CouponEvent event;
    private CouponInventory inventory;
    private CouponCommandService service;

    @BeforeEach
    void setUp() {
        counter = BeanUtils.instantiateClass(CouponDailyLimit.class);
        event = BeanUtils.instantiateClass(CouponEvent.class);
        ReflectionTestUtils.setField(event, "businessDate", TODAY);
        ReflectionTestUtils.setField(event, "status", CouponEventStatus.ACTIVE);
        ReflectionTestUtils.setField(event, "usableStartAt", TODAY.atTime(11, 0));
        ReflectionTestUtils.setField(event, "usableEndAt", TODAY.atTime(14, 0));
        inventory = BeanUtils.instantiateClass(CouponInventory.class);
        ReflectionTestUtils.setField(inventory, "status", CouponInventoryStatus.AVAILABLE);
        ReflectionTestUtils.setField(inventory, "couponCode", UUID.randomUUID());
        when(counters.findByMemberIdAndBusinessDate(17L, TODAY)).thenReturn(Optional.of(counter));
        when(events.findByIdForUpdate(81L)).thenReturn(Optional.of(event));
        when(members.findById(17L)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
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
        var result = service.issue(17L, 81L);
        assertThat(result.memberCouponId()).isEqualTo(1001L);
        assertThat(result.issueTime().toString()).isEqualTo("2026-10-10T12:00+09:00");
        assertThat(counter.getIssuedCount()).isEqualTo(1);
        assertThat(inventory.getStatus()).isEqualTo(CouponInventoryStatus.ISSUED);
        var saved = org.mockito.ArgumentCaptor.forClass(MemberCoupon.class);
        verify(coupons).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCouponCode()).isEqualTo(inventory.getCouponCode());
        assertThat(saved.getValue().getUsableEndAt()).isEqualTo(event.getUsableEndAt());
        assertThat(saved.getValue().getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
    }

    @Test
    void 이벤트가_없으면_찾을_수_없다는_오류로_거절한다() {
        when(events.findByIdForUpdate(81L)).thenReturn(Optional.empty());
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
        serviceAt(TODAY.atTime(11, 0)).issue(17L, 81L);
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
        when(members.findById(17L)).thenReturn(Optional.empty());
        expect(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
        when(members.findById(17L)).thenReturn(Optional.of(member(MemberStatus.SUSPENDED)));
        expect(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
    }

    @Test
    void 기존_발급과_일일_한도와_재고_소진을_구분한다() {
        when(coupons.existsByCouponEventIdAndMemberId(81L, 17L)).thenReturn(true);
        expect(CouponErrorCode.EVENT_ALREADY_ISSUED);
        when(coupons.existsByCouponEventIdAndMemberId(81L, 17L)).thenReturn(false);
        ReflectionTestUtils.setField(counter, "issuedCount", 3);
        expect(CouponErrorCode.DAILY_LIMIT_REACHED);
        ReflectionTestUtils.setField(counter, "issuedCount", 0);
        when(inventories.findFirstByCouponEventIdAndStatusOrderBySequenceNoAsc(81L, CouponInventoryStatus.AVAILABLE))
                .thenReturn(Optional.empty());
        expect(CouponErrorCode.INVENTORY_EXHAUSTED);
    }

    @Test
    void DB_잠금_실패를_그대로_전달한다() {
        var failure = new QueryTimeoutException("lock timeout");
        when(events.findByIdForUpdate(81L)).thenThrow(failure);
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
        CouponCommandService transactionalService = (CouponCommandService) proxy.getProxy();

        assertThatThrownBy(() -> transactionalService.issue(17L, 81L)).isSameAs(failure);
    }

    @Test
    void 재고_잠금_뒤_시각을_다시_검사한다() {
        Clock changing = mock(Clock.class);
        Clock noon = Clock.fixed(TODAY.atTime(12, 0).atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        Clock end = Clock.fixed(TODAY.atTime(14, 0).atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        when(changing.withZone(ClockConfig.ZONE)).thenReturn(noon, noon, end);
        service = serviceWith(changing);
        expect(CouponErrorCode.COUPON_NOT_AVAILABLE);
        assertThat(inventory.getStatus()).isEqualTo(CouponInventoryStatus.AVAILABLE);
    }

    private void expect(CouponErrorCode code) {
        assertThatThrownBy(() -> service.issue(17L, 81L)).isInstanceOfSatisfying(CouponException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private CouponCommandService serviceAt(LocalDateTime now) {
        return serviceWith(Clock.fixed(now.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE));
    }

    private CouponCommandService serviceWith(Clock clock) {
        var service = new CouponCommandService(coupons, counters, members, events, inventories,
                new CouponQrImageEncoder(), clock);
        ReflectionTestUtils.setField(service, "dailyLimit", 3);
        ReflectionTestUtils.setField(service, "issueStartTime", LocalTime.of(11, 0));
        ReflectionTestUtils.setField(service, "issueEndTime", LocalTime.of(14, 0));
        return service;
    }

    private MemberInfo member(MemberStatus status) {
        return new MemberInfo(17L, "회원", status, false, false, false, null, null, null, null);
    }
}
