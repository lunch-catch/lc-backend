package com.lunchcatch.adserving.impression.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.impression.repository.DispatchLeaseRepository;
import com.lunchcatch.adserving.impression.repository.ImpressionDispatchRepository;
import com.lunchcatch.adserving.impression.repository.ImpressionDispatchRepository.PendingImpression;
import com.lunchcatch.adserving.impression.repository.ImpressionDispatchRepository.PendingSummary;
import com.lunchcatch.global.config.BacklogMetrics;
import com.lunchcatch.billing.contract.DeductionOutcome;
import com.lunchcatch.billing.contract.DeductionRequest;
import com.lunchcatch.billing.contract.DeductionStatus;
import com.lunchcatch.billing.contract.PointDeductor;
import com.lunchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ImpressionDispatchServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    private final DispatchLeaseRepository lease = mock(DispatchLeaseRepository.class);
    private final ImpressionDispatchRepository impressions = mock(ImpressionDispatchRepository.class);
    private final PointDeductor pointDeductor = mock(PointDeductor.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
    private final ImpressionDispatchService service = new ImpressionDispatchService(
            lease, impressions, pointDeductor, new ImmediateTransactions(), meterRegistry, clock,
            new BacklogMetrics(meterRegistry, clock));

    // 밀린 양은 넘기기의 결과와 상관없이 회차마다 센다. 따로 보지 않는 시험은 밀린 것이 없는 것으로 둔다
    @BeforeEach
    void 밀린_것이_없다() {
        when(impressions.countPending(any())).thenReturn(new PendingSummary(0, null));
    }

    @Test
    void 다른_서버가_점유_중이면_넘기지_않는다() {
        when(lease.acquire(any())).thenReturn(false);

        service.dispatchPending();

        verifyNoInteractions(impressions, pointDeductor);
        verify(lease, never()).release();
    }

    @Test
    void 점유를_확인하지_못하면_넘기지_않고_예외를_올리지_않는다() {
        when(lease.acquire(any())).thenThrow(new RedisConnectionFailureException("Valkey 가 멈췄다"));

        assertThatCode(service::dispatchPending).doesNotThrowAnyException();

        verifyNoInteractions(impressions, pointDeductor);
    }

    @Test
    void 어제_00시_이후의_다시_넘길_시각이_지난_시도_횟수_상한_아래인_노출을_1000건까지_읽는다() {
        when(lease.acquire(Duration.ofSeconds(30))).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        service.dispatchPending();

        verify(impressions).findPending(TODAY.minusDays(1).atStartOfDay(), NOW, 10, PointDeductor.MAX_BATCH_SIZE);
        verifyNoInteractions(pointDeductor);
        verify(lease).release();
    }

    @Test
    void 판정이_커밋된_노출은_넘긴_시각을_적고_실패한_노출은_시도_횟수를_올린다() {
        PendingImpression billed = pending(1);
        PendingImpression failed = pending(2);
        when(lease.acquire(any())).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(List.of(billed, failed));
        when(pointDeductor.deductAll(anyList())).thenReturn(List.of(
                DeductionOutcome.judged(billed.serveId(), DeductionStatus.BILLED),
                DeductionOutcome.failed(failed.serveId())));

        service.dispatchPending();

        verify(pointDeductor).deductAll(List.of(request(billed), request(failed)));
        verify(impressions).markDispatched(List.of(billed), NOW);
        verify(impressions).markFailed(List.of(failed), NOW);
        assertThat(meterRegistry.counter(ImpressionDispatchService.DISPATCHED_METRIC).count()).isEqualTo(1);
        assertThat(meterRegistry.counter(ImpressionDispatchService.FAILED_METRIC).count()).isEqualTo(1);
        verify(lease).release();
    }

    // 정산 한 번에 걸린 시간과 넘긴 건수로 릴레이의 처리량을 가른다(#997 부하 시험에서 릴레이가 초당 약 20건에 머물렀다)
    @Test
    void 정산에_한_번_넘길_때마다_걸린_시간과_넘긴_건수를_지표로_낸다() {
        PendingImpression first = pending(1);
        PendingImpression second = pending(2);
        when(lease.acquire(any())).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(List.of(first, second));
        when(pointDeductor.deductAll(anyList())).thenAnswer(invocation -> judgedAll(invocation.getArgument(0)));

        service.dispatchPending();

        assertThat(meterRegistry.get(ImpressionDispatchService.DEDUCT_DURATION_METRIC).timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get(ImpressionDispatchService.DEDUCT_BATCH_METRIC).summary().totalAmount()).isEqualTo(2);
    }

    @Test
    void 정산이_예외를_던지면_아무것도_적지_않고_다음_주기에_같은_묶음을_다시_넘긴다() {
        when(lease.acquire(any())).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(List.of(pending(1)));
        when(pointDeductor.deductAll(anyList())).thenThrow(new QueryTimeoutException("정산의 Valkey 가 늦다"));

        assertThatCode(service::dispatchPending).doesNotThrowAnyException();

        verify(impressions, never()).markDispatched(anyList(), any());
        verify(impressions, never()).markFailed(anyList(), any());
        assertThat(meterRegistry.counter(ImpressionDispatchService.ERROR_METRIC).count()).isEqualTo(1);
        verify(lease).release();
    }

    @Test
    void 한_묶음이_가득_차면_점유를_연장하고_다음_묶음을_넘긴다() {
        List<PendingImpression> full = IntStream.rangeClosed(1, PointDeductor.MAX_BATCH_SIZE).mapToObj(this::pending).toList();
        List<PendingImpression> rest = List.of(pending(5_000));
        when(lease.acquire(any())).thenReturn(true);
        when(lease.renew(Duration.ofSeconds(30))).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(full, rest);
        when(pointDeductor.deductAll(anyList())).thenAnswer(invocation -> judgedAll(invocation.getArgument(0)));

        service.dispatchPending();

        verify(pointDeductor, times(2)).deductAll(anyList());
        verify(lease).renew(Duration.ofSeconds(30));
        assertThat(meterRegistry.counter(ImpressionDispatchService.DISPATCHED_METRIC).count())
                .isEqualTo(PointDeductor.MAX_BATCH_SIZE + 1);
    }

    // 실패한 노출은 다시 넘길 시각이 미뤄지므로 같은 회차에 바로 다시 넘기지 않는다(REL-2-06)
    @Test
    void 가득_찬_묶음에_실패가_있으면_같은_회차에_이어서_넘기지_않는다() {
        List<PendingImpression> full = IntStream.rangeClosed(1, PointDeductor.MAX_BATCH_SIZE).mapToObj(this::pending).toList();
        when(lease.acquire(any())).thenReturn(true);
        when(lease.renew(any())).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(full);
        when(pointDeductor.deductAll(anyList())).thenAnswer(invocation -> {
            List<DeductionRequest> requests = invocation.getArgument(0);
            return requests.stream().map(request -> DeductionOutcome.failed(request.serveId())).toList();
        });

        service.dispatchPending();

        verify(pointDeductor, times(1)).deductAll(anyList());
        verify(lease, never()).renew(any());
    }

    @Test
    void 점유를_연장하지_못하면_그_회차를_끝낸다() {
        List<PendingImpression> full = IntStream.rangeClosed(1, PointDeductor.MAX_BATCH_SIZE).mapToObj(this::pending).toList();
        when(lease.acquire(any())).thenReturn(true);
        when(lease.renew(any())).thenReturn(false);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(full);
        when(pointDeductor.deductAll(anyList())).thenAnswer(invocation -> judgedAll(invocation.getArgument(0)));

        service.dispatchPending();

        verify(pointDeductor, times(1)).deductAll(anyList());
    }

    @Test
    void 밀린_노출_수를_지표로_낸다() {
        when(lease.acquire(any())).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(impressions.countPending(TODAY.minusDays(1).atStartOfDay()))
                .thenReturn(new PendingSummary(7, NOW.minusSeconds(4)));

        service.dispatchPending();

        assertThat(meterRegistry.get(ImpressionDispatchService.BACKLOG_METRIC).gauge().value()).isEqualTo(7);
        assertThat(meterRegistry.get("outbox.relay.backlog").tag("relay", ImpressionDispatchService.RELAY_NAME)
                .gauge().value()).isEqualTo(7);
        assertThat(meterRegistry.get("outbox.relay.oldest.age").tag("relay", ImpressionDispatchService.RELAY_NAME)
                .gauge().value()).isEqualTo(4);
    }

    @Test
    void 정산이_실패해도_밀린_양은_센다() {
        when(lease.acquire(any())).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(List.of(pending(1)));
        when(pointDeductor.deductAll(anyList())).thenThrow(new QueryTimeoutException("정산의 Valkey 가 늦다"));
        when(impressions.countPending(TODAY.minusDays(1).atStartOfDay()))
                .thenReturn(new PendingSummary(3, NOW.minusSeconds(8)));

        service.dispatchPending();

        assertThat(relayGauge("outbox.relay.backlog")).isEqualTo(3);
        assertThat(relayGauge("outbox.relay.oldest.age")).isEqualTo(8);
    }

    @Test
    void 밀린_양을_세지_못하면_지난_값을_두고_오류로_센다() {
        밀린_노출을_한_번_센다();
        when(lease.acquire(any())).thenReturn(true);
        when(impressions.countPending(any())).thenThrow(new QueryTimeoutException("DB 가 늦다"));

        assertThatCode(service::dispatchPending).doesNotThrowAnyException();

        assertThat(relayGauge("outbox.relay.backlog")).isEqualTo(7);
        assertThat(meterRegistry.counter(ImpressionDispatchService.ERROR_METRIC).count()).isEqualTo(1);
        verify(lease).release();
    }

    @Test
    void 다른_서버가_점유하면_이_서버의_밀린_양은_비운다() {
        밀린_노출을_한_번_센다();
        when(lease.acquire(any())).thenReturn(false);

        service.dispatchPending();

        assertThat(relayGauge("outbox.relay.backlog")).isZero();
        assertThat(relayGauge("outbox.relay.oldest.age")).isZero();
    }

    @Test
    void 점유를_확인하지_못하면_밀린_양을_그대로_두어_대기_시간이_계속_는다() {
        밀린_노출을_한_번_센다();
        when(lease.acquire(any())).thenThrow(new RedisConnectionFailureException("Valkey 가 멈췄다"));

        service.dispatchPending();

        assertThat(relayGauge("outbox.relay.backlog")).isEqualTo(7);
        assertThat(relayGauge("outbox.relay.oldest.age")).isEqualTo(4);
    }

    private void 밀린_노출을_한_번_센다() {
        when(lease.acquire(any())).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(impressions.countPending(TODAY.minusDays(1).atStartOfDay()))
                .thenReturn(new PendingSummary(7, NOW.minusSeconds(4)));
        service.dispatchPending();
        reset(lease);
    }

    private double relayGauge(String name) {
        return meterRegistry.get(name).tag("relay", ImpressionDispatchService.RELAY_NAME).gauge().value();
    }

    @Test
    void 밀린_노출이_없으면_대기_시간은_0_이다() {
        when(lease.acquire(any())).thenReturn(true);
        when(impressions.findPending(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(impressions.countPending(TODAY.minusDays(1).atStartOfDay())).thenReturn(new PendingSummary(0, null));

        service.dispatchPending();

        assertThat(meterRegistry.get("outbox.relay.oldest.age").tag("relay", ImpressionDispatchService.RELAY_NAME)
                .gauge().value()).isZero();
    }

    private PendingImpression pending(int n) {
        return new PendingImpression(new UUID(0, n), 1_000L + n, 101L, TODAY);
    }

    private static DeductionRequest request(PendingImpression impression) {
        return new DeductionRequest(impression.serveId(), impression.campaignId(), impression.businessDate());
    }

    private static List<DeductionOutcome> judgedAll(List<DeductionRequest> requests) {
        List<DeductionOutcome> outcomes = new ArrayList<>();
        requests.forEach(request -> outcomes.add(DeductionOutcome.judged(request.serveId(), DeductionStatus.BILLED)));
        return outcomes;
    }

    // 콜백을 그 자리에서 돌린다. 마무리 쓰기가 한 트랜잭션으로 묶이는지는 통합 테스트가 본다.
    private static final class ImmediateTransactions extends TransactionTemplate {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    }
}
