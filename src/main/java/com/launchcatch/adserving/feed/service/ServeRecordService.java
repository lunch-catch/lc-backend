package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.feed.ServedFeed;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

/*
 * 서빙 로그와 그날 첫 요청 위치를 응답 뒤에 비동기로 쓴다.
 *
 * 서빙 로그는 판정과 과금에 쓰이지 않는다(100행). 동기로 쓰면 초당 5,000행의 쓰기가 피드 응답 지연에 들어가고
 * DB 쓰기 실패가 피드 실패가 된다.
 * 임시(#230): 대기열이 차면 기다리지 않고 버린다. 기다리면 피드 응답이 늦어진다. 버린 건수를 세어 유실률을 잰다.
 *
 * 스레드 풀을 빈으로 등록하지 않고 여기서 갖는다. Executor 빈이 하나라도 있으면 Spring Boot 가 기본
 * applicationTaskExecutor 를 만들지 않아 다른 도메인의 비동기 처리까지 바뀐다.
 */
@Slf4j
@Service
public class ServeRecordService {

    static final String DROPPED_METRIC = "adserving.serve_log.dropped";

    private static final int THREADS = 2;

    /*
     * 임시(#230): 피드 1,000회 분량이다.
     * 피크 500 TPS 에서 2초를 버틴다.
     */
    private static final int QUEUE_CAPACITY = 1000;
    private static final long SHUTDOWN_WAIT_SECONDS = 20;

    private final ServeLogRepository serveLogRepository;
    private final ExecutorService executor;
    private final MeterRegistry meterRegistry;

    @Autowired
    public ServeRecordService(ServeLogRepository serveLogRepository, MeterRegistry meterRegistry) {
        this(serveLogRepository, newExecutor(), meterRegistry);
    }

    ServeRecordService(ServeLogRepository serveLogRepository, ExecutorService executor, MeterRegistry meterRegistry) {
        this.serveLogRepository = serveLogRepository;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
    }

    /*
     * 피드 1회가 작업 하나다.
     * 대기열이 차면 거절해 이 서비스가 버리고 센다.
     */
    public void recordAsync(ServedFeed feed) {
        try {
            executor.execute(() -> write(feed));
        } catch (RejectedExecutionException e) {
            countDropped("queue_full");
        }
    }

    // 배포로 내려갈 때 대기열에 남은 작업을 마칠 때까지 기다린다
    @PreDestroy
    public void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("serve log executor did not finish in {}s", SHUTDOWN_WAIT_SECONDS);
        }
    }

    private void write(ServedFeed feed) {
        try {
            if (!feed.cards().isEmpty()) {
                serveLogRepository.saveServeLogs(feed);
            }
            serveLogRepository.saveVisit(feed);
        } catch (DataAccessException e) {
            countDropped("write_failed");
            log.warn("serve log write failed. feedId={}, cause={}", feed.feedId(), e.getClass().getSimpleName());
        }
    }

    private void countDropped(String reason) {
        meterRegistry.counter(DROPPED_METRIC, "reason", reason).increment();
    }

    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(THREADS, THREADS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), new CustomizableThreadFactory("serve-log-"),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
