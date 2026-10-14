package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/*
 * 서빙 로그와 그날 첫 요청 위치를 피드 응답 뒤에 쓴다.
 * 동기로 쓰면 초당 5,000행의 쓰기가 피드 응답 지연에 들어가고, DB 쓰기가 실패하면 피드까지 실패한다.
 * 노출 판정과 과금은 serve 기록으로 하므로 여기서 빠진 행은 진단의 "기록 없음" 으로만 드러난다.
 * 대기열이 차거나 쓰기가 실패하면 버리고 센다. 세지 않으면 유실률을 잴 수 없다.
 * 기다리게 두지 않는 것은 DB 가 느려질 때 그 지연이 피드 응답으로 번지기 때문이다.
 *
 * 실행기를 빈으로 등록하지 않는다. Executor 빈이 생기면 스프링 부트의 기본 실행기가 꺼진다.
 */
@Slf4j
@Service
public class ServeLogWriter {

    static final String DROPPED_METRIC = "adserving_serve_log_dropped_total";

    // 대기열 1,000 은 피드 1,000회분(카드 10,000장)이고 스레드 2개와 함께 잠정값이다. 부하 시험으로 정한다
    private static final int THREADS = 2;
    private static final int QUEUE_CAPACITY = 1_000;
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final Executor executor;
    private final ServeLogRepository serveLogRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public ServeLogWriter(ServeLogRepository serveLogRepository, MeterRegistry meterRegistry, Clock clock) {
        this(newExecutor(), serveLogRepository, meterRegistry, clock);
    }

    ServeLogWriter(Executor executor, ServeLogRepository serveLogRepository, MeterRegistry meterRegistry,
            Clock clock) {
        this.executor = executor;
        this.serveLogRepository = serveLogRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public void write(Long memberId, LocalDate businessDate, List<ServeRecord> records, BigDecimal latRounded,
            BigDecimal lngRounded) {
        try {
            executor.execute(() -> save(memberId, businessDate, records, latRounded, lngRounded));
        } catch (RejectedExecutionException e) {
            countDropped("queue_full", records.size());
        }
    }

    private void save(Long memberId, LocalDate businessDate, List<ServeRecord> records, BigDecimal latRounded,
            BigDecimal lngRounded) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            if (!records.isEmpty()) {
                serveLogRepository.saveAll(records, now);
            }
            serveLogRepository.insertVisitIfAbsent(businessDate, memberId, latRounded, lngRounded, now);
        } catch (DataAccessException e) {
            log.warn("serve log dropped. memberId={}, cards={}", memberId, records.size(), e);
            countDropped("write_failed", records.size());
        }
    }

    // 배포로 내려갈 때 대기열에 남은 로그를 마저 쓴다
    @PreDestroy
    void shutdown() throws InterruptedException {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
            service.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), Thread.ofPlatform().name("serve-log-", 0).factory());
    }

    private void countDropped(String reason, int cards) {
        meterRegistry.counter(DROPPED_METRIC, "reason", reason).increment(cards);
    }
}
