package com.lunchcatch.adserving.impression.service;

import com.lunchcatch.adserving.config.ImpressionDispatchProperties;
import com.lunchcatch.adserving.impression.repository.DispatchLeaseRepository;
import com.lunchcatch.adserving.impression.repository.ImpressionDispatchRepository;
import com.lunchcatch.adserving.impression.repository.ImpressionDispatchRepository.PendingImpression;
import com.lunchcatch.adserving.impression.repository.ImpressionDispatchRepository.PendingSummary;
import com.lunchcatch.billing.contract.DeductionOutcome;
import com.lunchcatch.billing.contract.DeductionRequest;
import com.lunchcatch.billing.contract.PointDeductor;
import com.lunchcatch.global.config.BacklogMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 커밋된 유효 노출을 모아 정산에 넘긴다(노출 수집 v2, #1010). 노출 요청은 정산을 부르지 않는다.
 *
 * 한 회차는 점유 → 넘길 노출 읽기 → 트랜잭션 밖에서 deductAll → 결과 적기 순서다. 정산은 트랜잭션 안에서 부르면 거절한다.
 * 판정이 커밋된 노출에는 넘긴 시각을 적고 실패한 노출은 시도 횟수만 올린다. 둘은 한 트랜잭션이다.
 * 결과를 적기 전에 멈추면 같은 노출을 다시 넘긴다. 정산이 serveId 로 멱등이라 과금은 한 번이고 처음 판정을 돌려준다.
 * 묶음이 가득 차면 점유를 연장하고 다음 묶음을 넘긴다. 밀렸다 풀릴 때 따라잡는다.
 */
@Slf4j
@Service
public class ImpressionDispatchService {

    static final String DISPATCHED_METRIC = "adserving_impression_dispatched_total";
    static final String FAILED_METRIC = "adserving_impression_dispatch_failed_total";
    static final String ERROR_METRIC = "adserving_impression_dispatch_error_total";
    static final String BACKLOG_METRIC = "adserving_impression_dispatch_backlog";
    static final String DEDUCT_DURATION_METRIC = "adserving_impression_dispatch_deduct_seconds";
    static final String DEDUCT_BATCH_METRIC = "adserving_impression_dispatch_deduct_batch";
    // 공통 지표의 릴레이 이름이다. 이름별 기준을 둘 때 application.yml 의 lunchcatch.observability.targets.relay 키로 쓴다
    static final String RELAY_NAME = "adserving.impression";

    /*
     * 배치 서버 하나가 릴레이를 맡는 임대의 시간이다.
     * 묶음을 넘기는 동안 풀리면 다른 서버가 같은 노출을 함께 넘기므로 설정으로 두지 않는다.
     * 묶음마다 늘리고 실행이 끝나면 푼다.
     */
    static final Duration LEASE_TTL = Duration.ofSeconds(30);

    // 주기와 시도 횟수는 설정(adserving.impression.dispatch)이 갖는다
    private final ImpressionDispatchProperties properties;
    private final DispatchLeaseRepository lease;
    private final ImpressionDispatchRepository impressions;
    private final PointDeductor pointDeductor;
    private final TransactionTemplate transactions;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicReference<LocalDateTime> oldestPending = new AtomicReference<>();

    public ImpressionDispatchService(DispatchLeaseRepository lease, ImpressionDispatchRepository impressions,
                                     PointDeductor pointDeductor, TransactionTemplate transactions,
                                     MeterRegistry meterRegistry, Clock clock, BacklogMetrics backlogMetrics,
                                     ImpressionDispatchProperties properties) {
        this.properties = properties;
        this.lease = lease;
        this.impressions = impressions;
        this.pointDeductor = pointDeductor;
        this.transactions = transactions;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        meterRegistry.gauge(BACKLOG_METRIC, backlog);
        backlogMetrics.relay(RELAY_NAME, backlog::get, oldestPending::get);
        // 사건이 없어도 0 으로 보이게 기동 때 만든다
        List.of(DISPATCHED_METRIC, FAILED_METRIC, ERROR_METRIC).forEach(meterRegistry::counter);
    }

    /*
     * 점유를 확인하지 못하면(Valkey 장애) 이 회차를 건너뛴다. 정산의 소진 카운터도 Valkey 라 어차피 차감이 되지 않는다.
     * 정산이 예외를 던지면 아무것도 적지 않고 회차를 끝낸다. 다음 주기에 같은 묶음을 다시 넘긴다.
     * 두 경우 모두 예외를 올리지 않는다. 스케줄러가 다음 주기를 그대로 돌게 한다(장애 처리 설계 "노출 릴레이").
     */
    public void dispatchPending() {
        if (!acquire()) {
            return;
        }
        LocalDateTime from = LocalDate.now(clock).minusDays(1).atStartOfDay();
        try {
            boolean more = dispatchBatch(from);
            while (more && lease.renew(LEASE_TTL)) {
                more = dispatchBatch(from);
            }
        } catch (RuntimeException e) {
            meterRegistry.counter(ERROR_METRIC).increment();
            log.warn("event=ADSERVING_IMPRESSION_DISPATCH_FAILED fallback=RETRY_NEXT_RUN", e);
        }
        try {
            countBacklog(from);
        } finally {
            release();
        }
    }

    /*
     * 넘기기가 실패해도 센다. 정산이 계속 실패해 노출이 쌓이는 때가 밀린 양을 가장 봐야 하는 때다.
     * 세지 못하면(DB 장애) 지난 값을 그대로 두고 오류로 센다. 가장 이른 시각이 그대로라 대기 시간은 계속 는다.
     */
    private void countBacklog(LocalDateTime from) {
        try {
            PendingSummary pending = impressions.countPending(from);
            backlog.set(pending.count());
            oldestPending.set(pending.oldest());
        } catch (RuntimeException e) {
            meterRegistry.counter(ERROR_METRIC).increment();
            log.warn("event=ADSERVING_IMPRESSION_BACKLOG_COUNT_FAILED fallback=KEEP_LAST", e);
        }
    }

    /*
     * 한 묶음을 넘기고 결과를 적는다. 묶음이 가득 찼고 실패가 없어 더 남았을 수 있으면 true 다.
     * 실패한 노출은 다시 넘길 시각을 미뤘으므로 같은 회차에 이어서 넘기지 않는다. 실패가 몰릴 때 정산을 거듭 부르지 않는다(REL-2-06).
     */
    private boolean dispatchBatch(LocalDateTime from) {
        List<PendingImpression> batch = impressions.findPending(from, LocalDateTime.now(clock), properties.maxAttempts(),
                PointDeductor.MAX_BATCH_SIZE);
        if (batch.isEmpty()) {
            return false;
        }
        /*
         * 정산 한 번에 걸린 시간과 넘긴 건수로 릴레이의 처리량을 가른다(#997).
         * 정산이 예외를 던져도 걸린 시간은 남긴다.
         */
        meterRegistry.summary(DEDUCT_BATCH_METRIC).record(batch.size());
        Timer.Sample sample = Timer.start(meterRegistry);
        List<DeductionOutcome> outcomes;
        try {
            outcomes = pointDeductor.deductAll(batch.stream()
                    .map(impression -> new DeductionRequest(impression.serveId(), impression.campaignId(), impression.businessDate()))
                    .toList());
        } finally {
            sample.stop(meterRegistry.timer(DEDUCT_DURATION_METRIC));
        }
        Map<UUID, PendingImpression> byServeId = batch.stream()
                .collect(Collectors.toMap(PendingImpression::serveId, Function.identity()));
        List<PendingImpression> dispatched = new ArrayList<>();
        List<PendingImpression> failed = new ArrayList<>();
        for (DeductionOutcome outcome : outcomes) {
            PendingImpression impression = byServeId.get(outcome.serveId());
            if (impression != null) {
                (outcome.failed() ? failed : dispatched).add(impression);
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        transactions.executeWithoutResult(status -> {
            impressions.markDispatched(dispatched, now);
            impressions.markFailed(failed, now);
        });
        meterRegistry.counter(DISPATCHED_METRIC).increment(dispatched.size());
        meterRegistry.counter(FAILED_METRIC).increment(failed.size());
        return failed.isEmpty() && batch.size() == PointDeductor.MAX_BATCH_SIZE;
    }

    /*
     * 다른 서버가 점유했으면 그 서버가 넘기고 있으니 이 서버가 지난번에 센 밀린 양은 낡았다. 비워서 지표에 남지 않게 한다.
     * 점유를 확인하지 못했으면(Valkey 장애) 아무도 넘기지 못하므로 그대로 두어 대기 시간이 계속 늘게 한다.
     */
    private boolean acquire() {
        try {
            boolean held = lease.acquire(LEASE_TTL);
            if (!held) {
                backlog.set(0);
                oldestPending.set(null);
            }
            return held;
        } catch (DataAccessException e) {
            meterRegistry.counter(ERROR_METRIC).increment();
            log.warn("event=ADSERVING_IMPRESSION_DISPATCH_LEASE_FAILED fallback=SKIP_RUN", e);
            return false;
        }
    }

    // 풀지 못해도 30초 뒤 만료된다. 다음 회차가 그만큼 늦을 뿐이다
    private void release() {
        try {
            lease.release();
        } catch (DataAccessException e) {
            log.warn("event=ADSERVING_IMPRESSION_DISPATCH_LEASE_RELEASE_FAILED fallback=WAIT_EXPIRY", e);
        }
    }
}
