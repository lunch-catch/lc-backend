package com.lunchcatch.adserving.impression.service;

import com.lunchcatch.adserving.contract.ImpressionServedEvent;
import com.lunchcatch.adserving.feed.repository.ImpressionCountRepository;
import com.lunchcatch.adserving.impression.entity.ImpressionLog;
import com.lunchcatch.adserving.impression.entity.ImpressionLogId;
import com.lunchcatch.adserving.impression.repository.ImpressionLogInsert.Row;
import com.lunchcatch.adserving.impression.repository.ImpressionLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 판정을 마친 노출을 응답 뒤에 묶어 impression_log 에 저장하는 큐다(노출 수집 v3, 요청 중 쓰기 설계 4장).
 * 클라이언트는 노출의 응답을 쓰지 않는다. 응답 뒤 저장에서 빠지는 몫은 유실 목표 0.1% 안에 둔다(feed-api-design.md 유실 목표).
 *
 * 큐가 차면 요청이 100ms 까지 기다리고 그래도 차 있으면 버리고 센다. 상한 없이 기다리면 DB 가 멈춘 동안
 * 노출 요청이 웹 서버의 스레드를 모두 잡아 같은 서버의 다른 API 도 멈춘다(장애 처리 설계 "DB 쓰기: 응답 뒤의 impression_log").
 * 묶음 쓰기가 실패하면 같은 묶음을 지수 백오프와 Full Jitter 로 10번까지 다시 쓴다(REL-2-06, REL-2-07). INSERT IGNORE 라 두 번 들어가지 않는다.
 * 그래도 실패하면 버리고 센다. 늘 실패하는 묶음이 작업자를 붙잡으면 큐가 차 모든 노출을 버리게 된다.
 * 서빙 로그 큐(ServeLogBuffer)는 실패한 묶음을 버리지만 노출은 과금 근거라 다시 쓴다.
 *
 * 작업자 스레드 하나가 500건이 차거나 첫 건부터 200ms 가 지나면 쓴다. 쓰는 동안 잠금을 쥐어 flush 가 쓰던 묶음을 기다리게 한다.
 * 작업자는 Clock 빈을 읽지 않는다. 저장 시각은 요청이 넣어 보낸다(#379).
 */
@Slf4j
@Service
public class ImpressionWriteBuffer implements SmartLifecycle {

    // 초당 1,000건의 10초 분량이다.
    static final int CAPACITY = 10_000;
    static final int BATCH_SIZE = 500;
    static final Duration MAX_WAIT = Duration.ofMillis(200);
    static final Duration OFFER_TIMEOUT = Duration.ofMillis(100);
    static final Duration FIRST_BACKOFF = Duration.ofMillis(200);
    // 큐가 넘치기 전에 다시 쓰도록 큐 분량(10초)보다 짧게 둔다.
    static final Duration MAX_BACKOFF = Duration.ofSeconds(5);
    static final int MAX_ATTEMPTS = 10;

    static final String QUEUE_METRIC = "adserving_impression_queue_size";
    static final String DROPPED_METRIC = "adserving_impression_dropped_total";
    static final String WRITE_FAILED_METRIC = "adserving_impression_write_failed_total";
    static final String LOST_METRIC = "adserving_impression_write_lost_total";
    // 요청의 캠페인이 serve 기록과 달랐던 횟수다. 판정은 serve 기록으로 한다(#323).
    static final String CAMPAIGN_MISMATCH_METRIC = "adserving_impression_campaign_mismatch_total";

    /*
     * 같은 노출이 다시 처리돼도 분석이 하나로 거르게 serveId 로 만든다.
     * 유효 노출은 serve 의 주인 것뿐이라 사용자를 넣지 않는다.
     */
    private static final String EVENT_ID_PREFIX = "impression:";

    private final BlockingQueue<QueuedImpression> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final ReentrantLock writing = new ReentrantLock();
    private final ImpressionLogRepository impressions;
    private final ImpressionCountRepository counts;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transactions;
    private final MeterRegistry meterRegistry;

    // 테스트가 쉬지 않게 바꾼다.
    Pause pause = Thread::sleep;

    private volatile boolean running;
    private Thread worker;

    public ImpressionWriteBuffer(ImpressionLogRepository impressions, ImpressionCountRepository counts,
                                 ApplicationEventPublisher publisher, TransactionTemplate transactions,
                                 MeterRegistry meterRegistry) {
        this.impressions = impressions;
        this.counts = counts;
        this.publisher = publisher;
        this.transactions = transactions;
        this.meterRegistry = meterRegistry;
        meterRegistry.gaugeCollectionSize(QUEUE_METRIC, List.of(), queue);
    }

    // 큐가 차면 100ms 까지 기다리고 그래도 넣지 못하면 버리고 센다.
    public void offer(QueuedImpression impression) {
        try {
            if (queue.offer(impression, OFFER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        meterRegistry.counter(DROPPED_METRIC).increment();
    }

    /*
     * 큐에 남은 것을 500건씩 모두 쓴다. 서버가 내려갈 때와 테스트가 부른다.
     * 다시 쓰지 않는다. 실패한 묶음은 쓰지 못한 행으로 센다.
     */
    public void flush() {
        writing.lock();
        try {
            List<QueuedImpression> batch = new ArrayList<>(BATCH_SIZE);
            while (queue.drainTo(batch, BATCH_SIZE) > 0) {
                if (!write(batch)) {
                    meterRegistry.counter(LOST_METRIC).increment(batch.size());
                }
                batch.clear();
            }
        } finally {
            writing.unlock();
        }
    }

    // n 번째 다시 쓰기 전에 쉬는 시간이다. random(0, min(5초, 200ms x 2^(n-1))) 이고 random 은 [0, 1) 이다.
    static Duration backoff(int attempt, double random) {
        long ceiling = FIRST_BACKOFF.toMillis() << Math.min(attempt - 1, 20);
        return Duration.ofMillis((long) (random * Math.min(MAX_BACKOFF.toMillis(), ceiling)));
    }

    /*
     * 한 트랜잭션으로 묶음을 넣고 새로 들어간 유효 노출의 퍼널 이벤트를 발행한다.
     * 이벤트는 트랜잭션 안에서 발행한다. 수신자가 AFTER_COMMIT 으로 받아 롤백된 묶음의 노출은 넘어가지 않는다.
     * 카운터는 되돌릴 수 없어 커밋된 뒤에 올린다(장애 처리 가이드 "롤백 안 되는 효과").
     * 커밋했으면 true 다. 커밋 뒤의 카운터가 실패해도 다시 쓰지 않는다.
     */
    private boolean write(List<QueuedImpression> batch) {
        List<QueuedImpression> fresh;
        try {
            fresh = transactions.execute(status -> {
                Set<ImpressionLogId> inserted = impressions.insertAllIgnore(batch.stream()
                        .map(impression -> new Row(impression.log(), impression.receivedAt()))
                        .toList());
                // 같은 키가 묶음에 둘이면 저장소가 처음 것만 넣으므로 처음 것만 센다.
                Set<ImpressionLogId> seen = new HashSet<>();
                List<QueuedImpression> freshValid = batch.stream()
                        .filter(impression -> seen.add(impression.log().getId()))
                        .filter(impression -> impression.log().isValid() && inserted.contains(impression.log().getId()))
                        .toList();
                freshValid.forEach(impression -> publisher.publishEvent(event(impression.log())));
                return freshValid;
            });
        } catch (RuntimeException e) {
            log.error("event=ADSERVING_IMPRESSION_WRITE_FAILED rowCount={} fallback=RETRY_BATCH", batch.size(), e);
            meterRegistry.counter(WRITE_FAILED_METRIC).increment();
            return false;
        }
        for (QueuedImpression impression : fresh) {
            counts.increment(impression.log().getBusinessDate(), impression.log().getCampaignId());
            if (impression.campaignMismatch()) {
                meterRegistry.counter(CAMPAIGN_MISMATCH_METRIC).increment();
            }
        }
        return true;
    }

    private static ImpressionServedEvent event(ImpressionLog log) {
        UUID serveId = log.getId().getServeId();
        return new ImpressionServedEvent(EVENT_ID_PREFIX + serveId, log.getDisplayedAt(), log.getId().getMemberId(),
                log.getCampaignId(), log.getStoreId(), serveId);
    }

    /*
     * 첫 건을 기다렸다가 500건이 차거나 200ms 가 지날 때까지 모아 쓴다.
     * 실패하면 같은 묶음을 쉬었다가 다시 쓴다. 서버가 내려가는 중이거나 상한에 닿으면 그 묶음은 쓰지 못한 행으로 센다.
     * 예상 밖의 예외도 잡는다. 작업자가 죽으면 큐가 차 모든 노출을 버리게 된다.
     */
    private void runWorker() {
        while (running) {
            writing.lock();
            try {
                List<QueuedImpression> batch = collect();
                if (!batch.isEmpty()) {
                    writeUntilDone(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("event=ADSERVING_IMPRESSION_WORKER_FAILED fallback=CONTINUE", e);
            } finally {
                writing.unlock();
            }
        }
    }

    private List<QueuedImpression> collect() throws InterruptedException {
        List<QueuedImpression> batch = new ArrayList<>(BATCH_SIZE);
        QueuedImpression first = queue.poll(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (batch.size() < BATCH_SIZE) {
            queue.drainTo(batch, BATCH_SIZE - batch.size());
            long remaining = deadline - System.nanoTime();
            if (batch.size() >= BATCH_SIZE || remaining <= 0) {
                break;
            }
            QueuedImpression next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batch.add(next);
        }
        return batch;
    }

    private void writeUntilDone(List<QueuedImpression> batch) throws InterruptedException {
        int attempt = 0;
        while (!write(batch)) {
            if (!running || attempt >= MAX_ATTEMPTS) {
                meterRegistry.counter(LOST_METRIC).increment(batch.size());
                return;
            }
            attempt++;
            pause.sleep(backoff(attempt, ThreadLocalRandom.current().nextDouble()).toMillis());
        }
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofPlatform().name("impression-writer").daemon(true).start(this::runWorker);
    }

    /*
     * 웹 서버가 요청을 다 받은 뒤 작업자를 멈추고 남은 것을 비운다.
     * 작업자를 interrupt 하지 않는다. 쓰던 묶음이 커넥션을 기다리다 실패하면 큐에서 이미 빠져 아래 비우기에도 없다(#783).
     * 다시 쓰는 중이던 작업자는 쉬는 시간(최대 5초)이 끝나면 나온다.
     */
    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // 웹 서버의 정상 종료(SmartLifecycle.DEFAULT_PHASE - 1024)보다 늦게 멈추도록 낮은 단계에 둔다.
    @Override
    public int getPhase() {
        return 0;
    }

    @FunctionalInterface
    interface Pause {
        void sleep(long millis) throws InterruptedException;
    }
}
