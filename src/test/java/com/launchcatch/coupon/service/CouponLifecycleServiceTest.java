package com.launchcatch.coupon.service;

import static com.launchcatch.coupon.service.CouponBatchFixture.DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.launchcatch.coupon.CouponTestProperties;
import com.launchcatch.coupon.contract.CouponIssueOpenedEvent;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.repository.CouponBatchRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.ops.contract.BatchAttemptGuard;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CouponLifecycleServiceTest {
    private final CouponBatchRepository batches = mock(CouponBatchRepository.class);
    private final CouponEventRepository events = mock(CouponEventRepository.class);
    private final BatchAttemptGuard guard = mock(BatchAttemptGuard.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final CouponEvent event = CouponBatchFixture.event();

    @Test
    void 재실행에서도_같은_오픈_이벤트_ID와_발급_시작_시각을_사용한다() {
        CouponLifecycleService service = service(DATE.atTime(11, 0));
        assertThat(service.open(DATE, 1)).isEqualTo(1);
        org.mockito.ArgumentCaptor<CouponIssueOpenedEvent> published = org.mockito.ArgumentCaptor.forClass(CouponIssueOpenedEvent.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue().eventId()).isEqualTo("coupon-issue-opened:1");
        assertThat(published.getValue().occurredAt()).isEqualTo(DATE.atTime(11, 0));
    }

    @Test
    void 처리_중_종료_시각을_넘겼으면_오픈하지_않는다() {
        assertThat(service(DATE.atTime(14, 0)).open(DATE, 1)).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void 종료는_운영_상태를_바꾸지_않고_시간_경계를_지킨다() {
        assertThatThrownBy(() -> service(DATE.atTime(13, 59)).close(DATE, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service(DATE.atTime(14, 0)).close(DATE, 1)).isZero();
        assertThat(event.getStatus()).isEqualTo(com.launchcatch.coupon.entity.CouponEventStatus.ACTIVE);
    }

    @Test
    void 마감_성공_로그는_커밋한_뒤에만_남긴다() {
        CouponLifecycleService service = service(DATE.atTime(14, 0));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try (var logs = new CouponLogCapture(CouponLifecycleService.class)) {
            org.mockito.Mockito.doAnswer(invocation -> {
                assertThat(logs.events).isEmpty();
                org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                        .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
                return null;
            }).when(manager).commit(any());

            service.close(DATE, 1);

            assertThat(logs.events).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage()).contains("event=COUPON_ISSUE_CLOSE_DONE"));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 마감_트랜잭션의_커밋이_실패하면_성공_로그를_남기지_않는다() {
        CouponLifecycleService service = service(DATE.atTime(14, 0));
        org.mockito.Mockito.doThrow(new org.springframework.transaction.UnexpectedRollbackException("commit failed"))
                .when(manager).commit(any());
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try (var logs = new CouponLogCapture(CouponLifecycleService.class)) {
            assertThatThrownBy(() -> service.close(DATE, 1))
                    .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);

            assertThat(logs.events).isEmpty();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private CouponLifecycleService service(LocalDateTime now) {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(batches.eventIds(DATE, 0)).thenReturn(List.of(1L));
        when(events.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        ZoneId zone = ZoneId.of("Asia/Seoul");
        return new CouponLifecycleService(batches, events, guard, publisher,
                Clock.fixed(now.atZone(zone).toInstant(), zone), manager, CouponTestProperties.defaults());
    }
}
