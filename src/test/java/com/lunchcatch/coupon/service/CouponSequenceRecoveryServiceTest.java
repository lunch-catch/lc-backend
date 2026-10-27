package com.lunchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.coupon.CouponTestProperties;
import com.lunchcatch.coupon.entity.CouponEvent;
import com.lunchcatch.coupon.entity.CouponEventStatus;
import com.lunchcatch.coupon.entity.CouponInventoryStatus;
import com.lunchcatch.coupon.repository.CouponEventRepository;
import com.lunchcatch.coupon.repository.CouponInventoryRepository;
import com.lunchcatch.coupon.repository.CouponSequenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CouponSequenceRecoveryServiceTest {

    private final CouponEventRepository events = mock(CouponEventRepository.class);
    private final CouponInventoryRepository inventories = mock(CouponInventoryRepository.class);
    private final CouponSequenceRepository sequences = mock(CouponSequenceRepository.class);
    private final CouponSequenceService sequenceService = mock(CouponSequenceService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T03:00:00Z"), ZoneOffset.UTC);
    private final CouponSequenceRecoveryService service = new CouponSequenceRecoveryService(
            CouponTestProperties.defaults(), events, inventories, sequences, sequenceService, transactions, clock);

    @BeforeEach
    void prepare() {
        CouponEvent event = BeanUtils.instantiateClass(CouponEvent.class);
        ReflectionTestUtils.setField(event, "businessDate", LocalDate.of(2026, 10, 21));
        ReflectionTestUtils.setField(event, "issueQuantity", 3);
        when(events.findForRecovery(81L)).thenReturn(Optional.of(event));
        when(inventories.findAvailableSequences(81L)).thenReturn(List.of(2, 3));
        when(inventories.countByCouponEventIdAndStatus(81L, CouponInventoryStatus.ISSUED)).thenReturn(1L);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void 영업일의_발급_종료_한_시간_후로_만료를_고정하고_복구_커밋_후_차단을_푼다() {
        assertThat(service.recover(81L)).isTrue();

        verify(sequences).rebuild(81L, List.of(2, 3), 1L, Instant.parse("2026-10-21T06:00:00Z").toEpochMilli());
        var order = org.mockito.Mockito.inOrder(transactions, sequenceService);
        order.verify(transactions).commit(any());
        order.verify(sequenceService).resume(81L);
    }

    @Test
    void 복구_실패와_그_뒤의_재시도_대기는_성공으로_반환하지_않는다() {
        doThrow(new IllegalStateException("Redis unavailable")).when(sequences)
                .rebuild(eq(81L), anyList(), anyLong(), anyLong());

        assertThat(service.recover(81L)).isFalse();
        assertThat(service.recover(81L)).isFalse();
        verify(sequences).rebuild(eq(81L), anyList(), anyLong(), anyLong());
        org.mockito.Mockito.verify(sequenceService, org.mockito.Mockito.never()).resume(81L);
    }

    @Test
    void 복구_실패는_메시지와_스택_없이_예외_종류와_타임아웃_사유만_남긴다() {
        doThrow(new org.springframework.dao.QueryTimeoutException("secret-token"))
                .when(sequences).rebuild(eq(81L), anyList(), anyLong(), anyLong());
        try (var logs = new CouponLogCapture(CouponSequenceRecoveryService.class)) {
            assertThat(service.recover(81L)).isFalse();

            assertThat(logs.events).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("exceptionType=QueryTimeoutException", "reason=TIMEOUT")
                        .doesNotContain("secret-token");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }

    @Test
    void 복구_DB_조회_실패는_메시지와_스택_없이_SQL_진단_정보를_남긴다() {
        when(events.findForRecovery(81L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "secret-connection", new java.sql.SQLException("secret-database", "08006", 2013)));
        try (var logs = new CouponLogCapture(CouponSequenceRecoveryService.class)) {
            assertThat(service.recover(81L)).isFalse();

            assertThat(logs.events).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("event=COUPON_ISSUANCE_RECOVERY_FAILED",
                        "couponEventId=81", "exceptionType=DataAccessResourceFailureException>SQLException",
                        "reason=OTHER", "sqlState=08006", "sqlErrorCode=2013", "origin=\"")
                        .doesNotContain("secret-connection", "secret-database", "origin=\"null\"");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }

    @Test
    void 이미_다른_복구가_진행_중이면_건너뛴_결과를_실패로_반환한다() {
        @SuppressWarnings("unchecked")
        Set<Long> recovering = (Set<Long>) ReflectionTestUtils.getField(service, "recovering");
        recovering.add(81L);

        assertThat(service.recover(81L)).isFalse();
        verifyNoInteractions(transactions, sequences);
    }

    @Test
    void 처리_기한이_끝난_요청은_복구를_시작하지_않는다() {
        assertThat(service.recover(81L, System.nanoTime() - 1)).isFalse();
        verifyNoInteractions(transactions, sequences);
    }

    @Test
    void 보존_기한이_지난_이벤트는_키를_재구축하거나_차단을_풀지_않는다() {
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneOffset.UTC));

        assertThat(service.recover(81L)).isFalse();
        verifyNoInteractions(sequences, sequenceService);
    }

    // 큐가 준비되지 않은 ACTIVE와 PAUSED 회차는 DB 기준으로 미리 만들고 차단 상태는 건드리지 않아야 한다.
    @ParameterizedTest
    @EnumSource(value = CouponEventStatus.class, names = {"ACTIVE", "PAUSED"})
    void 오픈_전에_큐가_없는_회차의_큐를_미리_만든다(CouponEventStatus status) {
        ReflectionTestUtils.setField(events.findForRecovery(81L).orElseThrow(), "status", status);
        when(sequences.availability(81L)).thenReturn(-1);

        assertThat(service.prepare(81L)).isTrue();

        verify(sequences).rebuild(81L, List.of(2, 3), 1L, Instant.parse("2026-10-21T06:00:00Z").toEpochMilli());
        verifyNoInteractions(transactions, sequenceService);
    }

    // 이미 READY나 SOLD_OUT인 큐는 진행 중인 예약을 지우지 않도록 다시 만들지 않아야 한다.
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void 이미_준비된_큐는_다시_만들지_않는다(int availability) {
        ReflectionTestUtils.setField(events.findForRecovery(81L).orElseThrow(), "status", CouponEventStatus.ACTIVE);
        when(sequences.availability(81L)).thenReturn(availability);

        assertThat(service.prepare(81L)).isFalse();

        verify(sequences, never()).rebuild(eq(81L), anyList(), anyLong(), anyLong());
    }

    // 종료된 회차는 Redis를 조회하지 않고 큐를 만들지 않아야 한다.
    @Test
    void 종료된_회차는_큐를_만들지_않는다() {
        ReflectionTestUtils.setField(events.findForRecovery(81L).orElseThrow(), "status", CouponEventStatus.ENDED);

        assertThat(service.prepare(81L)).isFalse();

        verifyNoInteractions(sequences);
    }

    // DB 재고 수가 발급 수량과 다르면 큐를 만들지 않고 실패를 호출자에게 알려야 한다.
    @Test
    void 재고_수가_발급_수량과_다르면_큐를_만들지_않는다() {
        ReflectionTestUtils.setField(events.findForRecovery(81L).orElseThrow(), "status", CouponEventStatus.ACTIVE);
        when(sequences.availability(81L)).thenReturn(-1);
        when(inventories.countByCouponEventIdAndStatus(81L, CouponInventoryStatus.ISSUED)).thenReturn(2L);

        assertThatThrownBy(() -> service.prepare(81L)).isInstanceOf(IllegalStateException.class);

        verify(sequences, never()).rebuild(eq(81L), anyList(), anyLong(), anyLong());
    }
}
