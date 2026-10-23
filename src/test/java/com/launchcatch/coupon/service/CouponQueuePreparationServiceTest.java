package com.launchcatch.coupon.service;

import static com.launchcatch.coupon.service.CouponBatchFixture.DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.repository.CouponBatchRepository;
import com.launchcatch.ops.contract.BatchAttemptGuard;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

// 회차 조회와 큐 생성을 모의 객체로 두고 회차별 트랜잭션과 실패 격리만 단위로 검증한다.
class CouponQueuePreparationServiceTest {

    private final CouponBatchRepository batches = mock(CouponBatchRepository.class);
    private final CouponSequenceRecoveryService recovery = mock(CouponSequenceRecoveryService.class);
    private final BatchAttemptGuard guard = mock(BatchAttemptGuard.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final CouponQueuePreparationService service =
            new CouponQueuePreparationService(batches, recovery, guard, manager);
    private final List<SimpleTransactionStatus> statuses = new ArrayList<>();

    // 오늘 회차 세 건을 돌려주고 트랜잭션 관리자가 회차마다 새 트랜잭션 상태를 주게 고정한다.
    @BeforeEach
    void prepare() {
        when(batches.eventIds(DATE, 0)).thenReturn(List.of(81L, 82L, 83L));
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            SimpleTransactionStatus status = new SimpleTransactionStatus();
            statuses.add(status);
            return status;
        });
    }

    // 회차마다 큐를 만들고 실제로 만든 회차 수만 처리 건수로 돌려줘야 한다.
    @Test
    void 회차마다_큐를_만들고_만든_회차_수를_돌려준다() {
        when(recovery.prepare(81L)).thenReturn(true);
        when(recovery.prepare(82L)).thenReturn(false);
        when(recovery.prepare(83L)).thenReturn(true);

        assertThat(service.prepare(DATE, 1)).isEqualTo(2);

        verify(guard, org.mockito.Mockito.times(3)).assertCurrentAttempt("COUPON_QUEUE_PREPARE", DATE, 1);
    }

    // 회차 하나의 큐 생성이 실패해도 그 회차만 롤백하고 다음 회차를 처리해야 한다.
    @Test
    void 회차_하나의_큐_생성이_실패해도_다음_회차를_처리한다() {
        when(recovery.prepare(81L)).thenReturn(true);
        when(recovery.prepare(82L)).thenThrow(new RedisConnectionFailureException("secret-redis"));
        when(recovery.prepare(83L)).thenReturn(true);
        try (var logs = new CouponLogCapture(CouponQueuePreparationService.class)) {
            assertThat(service.prepare(DATE, 1)).isEqualTo(2);

            assertThat(logs.events).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("event=COUPON_QUEUE_PREPARE_FAILED",
                        "couponEventId=82", "exceptionType=RedisConnectionFailureException")
                        .doesNotContain("secret-redis");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
        verify(recovery).prepare(83L);
        assertThat(statuses).extracting(SimpleTransactionStatus::isRollbackOnly).containsExactly(false, true, false);
    }

    // 실행 세대를 잃으면 큐를 만들지 않고 실패를 실행기에 전달해야 한다.
    @Test
    void 실행_세대를_잃으면_큐를_만들지_않고_실패를_전달한다() {
        doThrow(new IllegalStateException("attempt lost")).when(guard)
                .assertCurrentAttempt("COUPON_QUEUE_PREPARE", DATE, 1);

        assertThatThrownBy(() -> service.prepare(DATE, 1)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(recovery);
    }
}
