package com.lunchcatch.adserving.feed.service;

import com.lunchcatch.adserving.feed.repository.FeedVisitRepository;
import com.lunchcatch.adserving.feed.repository.FeedVisitRepository.Visit;
import com.lunchcatch.adserving.feed.repository.ServeLogRepository;
import com.lunchcatch.adserving.feed.repository.ServeLogRepository.Row;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/*
 * 피드 응답 뒤 serve_log 와 feed_visit_daily 를 쓰는 대기열이다. 서빙 로그는 판정과 과금에 쓰이지 않아 응답을 기다리게 하지 않는다(feed-api-design.md).
 * 대기열이 차면 버리고 센다. 기다리면 DB 가 느려질 때 피드 응답까지 느려진다(#315).
 * 프로세스가 죽거나 쓰기가 실패해도 빠진다. 유실률은 내려준 카드 수와 serve_log 건수를 대조해 잰다(유실 목표).
 *
 * 작업자 스레드 하나가 묶어서 쓴다. 종료할 때는 웹 서버가 요청을 다 받은 뒤 멈추고 남은 것을 비운다.
 * 크기와 묶음 수는 근거 없는 값(C)이라 부하 시험에서 바꾼다.
 * 작업자는 시계를 읽지 않는다. 기록 시각은 요청이 넣어 보낸다. 시계를 스파이로 바꾸는 테스트와 경합해 스텁이 엉뚱한 메서드에 걸린 적이 있다(#379).
 */
@Slf4j
@Service
public class ServeLogBuffer implements SmartLifecycle {

    // 피드 1회가 카드 10장이라 5,000건이 약 50,000행, 목표 처리량(초당 5,000행)으로 10초 분량이다.
    static final int CAPACITY = 5_000;
    static final int FEEDS_PER_BATCH = 50;

    static final String DROPPED_METRIC = "adserving_serve_log_dropped_total";
    static final String FAILED_METRIC = "adserving_serve_log_failed_total";
    static final String VISIT_FAILED_METRIC = "adserving_feed_visit_failed_total";

    private final BlockingQueue<ServedFeed> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final ServeLogRepository serveLogRepository;
    private final FeedVisitRepository feedVisitRepository;
    private final MeterRegistry meterRegistry;

    private volatile boolean running;
    private Thread worker;

    public ServeLogBuffer(ServeLogRepository serveLogRepository, FeedVisitRepository feedVisitRepository,
                          MeterRegistry meterRegistry) {
        this.serveLogRepository = serveLogRepository;
        this.feedVisitRepository = feedVisitRepository;
        this.meterRegistry = meterRegistry;
    }

    // 넣지 못하면 그 피드의 카드 수만큼 버린 것으로 센다.
    public void offer(ServedFeed feed) {
        if (!queue.offer(feed)) {
            meterRegistry.counter(DROPPED_METRIC).increment(Math.max(1, feed.cards().size()));
        }
    }

    // 대기열에서 한 묶음을 꺼내 쓴다. 꺼낸 것이 있으면 true 다.
    boolean flushOnce() {
        List<ServedFeed> batch = new ArrayList<>(FEEDS_PER_BATCH);
        queue.drainTo(batch, FEEDS_PER_BATCH);
        if (batch.isEmpty()) {
            return false;
        }
        write(batch);
        return true;
    }

    /*
     * 서빙 로그와 접속 위치는 서로 기대지 않아 따로 쓰고 따로 센다. 한쪽이 실패해도 다른 쪽은 쓴다.
     * 실패한 묶음은 다시 넣지 않고 버린다. 다시 넣으면 DB 가 멈춘 동안 같은 묶음이 계속 실패해 뒤의 것까지 막는다.
     */
    private void write(List<ServedFeed> batch) {
        List<Row> rows = batch.stream()
                .flatMap(f -> f.cards().stream().map(c -> new Row(f.businessDate(), f.feedId(), c.record(), c.progressRate(),
                        c.relevanceScore(), f.engineVersion())))
                .toList();
        if (!rows.isEmpty()) {
            try {
                serveLogRepository.insertAll(rows);
            } catch (RuntimeException e) {
                log.error("event=ADSERVING_FEED_SERVE_LOG_WRITE_FAILED feedCount={} rowCount={} fallback=DROP_ROWS", batch.size(), rows.size(), e);
                meterRegistry.counter(FAILED_METRIC).increment(rows.size());
            }
        }
        List<Visit> visits = batch.stream()
                .map(f -> new Visit(f.businessDate(), f.memberId(), f.latitude(), f.longitude(), f.requestedAt()))
                .toList();
        try {
            feedVisitRepository.insertAll(visits);
        } catch (RuntimeException e) {
            log.error("event=ADSERVING_FEED_VISIT_WRITE_FAILED visitCount={} fallback=DROP_ROWS", visits.size(), e);
            meterRegistry.counter(VISIT_FAILED_METRIC).increment(visits.size());
        }
    }

    // 첫 건이 올 때까지 기다렸다가 함께 쌓인 것을 묶음 하나로 쓴다.
    private void runWorker() {
        while (running) {
            try {
                ServedFeed first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<ServedFeed> batch = new ArrayList<>(FEEDS_PER_BATCH);
                batch.add(first);
                queue.drainTo(batch, FEEDS_PER_BATCH - 1);
                write(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofPlatform().name("serve-log-writer").daemon(true).start(this::runWorker);
    }

    /*
     * 웹 서버가 요청을 다 받은 뒤 멈추고 남은 것을 비운다.
     * 작업 스레드를 interrupt 하지 않는다. 쓰던 묶음이 커넥션을 기다리다 실패하면 대기열에서 이미 빠져 아래 비우기에도 없다(#783).
     * 작업 스레드는 poll 의 대기(200ms)가 끝나면 쓰던 묶음을 마치고 나온다.
     */
    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        while (flushOnce()) {
            // 남은 것을 모두 쓴다
        }
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
}
