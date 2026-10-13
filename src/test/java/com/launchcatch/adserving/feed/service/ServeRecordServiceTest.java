package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.launchcatch.adserving.engine.AssignedCard;
import com.launchcatch.adserving.engine.Candidate;
import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.feed.ServedFeed;
import com.launchcatch.adserving.feed.ServedFeed.ServedCard;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

@DisplayName("서빙 로그 비동기 저장")
class ServeRecordServiceTest {

    private final ServeLogRepository serveLogRepository = mock(ServeLogRepository.class);
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void 서빙_로그와_그날_접속_위치를_쓰고_내려갈_때_남은_작업을_마친다() throws InterruptedException {
        ServedFeed feed = feed(List.of(card()));

        ServeRecordService service = new ServeRecordService(serveLogRepository, meterRegistry);
        service.recordAsync(feed);
        service.shutdown();

        verify(serveLogRepository).saveServeLogs(feed);
        verify(serveLogRepository).saveVisit(feed);
    }

    @Test
    void 카드가_없으면_접속_위치만_쓴다() {
        ServedFeed feed = feed(List.of());

        service(directExecutor()).recordAsync(feed);

        verify(serveLogRepository, never()).saveServeLogs(any());
        verify(serveLogRepository).saveVisit(feed);
    }

    @Test
    void 대기열이_차면_버리고_버린_건수를_센다() {
        ExecutorService full = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("full")).when(full).execute(any());

        service(full).recordAsync(feed(List.of(card())));

        assertThat(dropped("queue_full")).isEqualTo(1);
        verify(serveLogRepository, never()).saveVisit(any());
    }

    @Test
    void 쓰기에_실패하면_버린_건수를_센다() {
        ServedFeed feed = feed(List.of(card()));
        doThrow(new QueryTimeoutException("db")).when(serveLogRepository).saveServeLogs(feed);

        service(directExecutor()).recordAsync(feed);

        assertThat(dropped("write_failed")).isEqualTo(1);
    }

    private ServeRecordService service(ExecutorService executor) {
        return new ServeRecordService(serveLogRepository, executor, meterRegistry);
    }

    private double dropped(String reason) {
        return meterRegistry.counter(ServeRecordService.DROPPED_METRIC, "reason", reason).count();
    }

    private static ServedFeed feed(List<ServedCard> cards) {
        return new ServedFeed(UUID.randomUUID(), 17L, LocalDate.of(2026, 10, 1),
                LocalDateTime.of(2026, 10, 1, 10, 15), 37.4979, 127.0276, cards);
    }

    private static ServedCard card() {
        Candidate candidate = mock(Candidate.class);
        return new ServedCard(UUID.randomUUID(), new AssignedCard(candidate, 420, SlotType.ALLOCATION, 1));
    }

    // 받은 작업을 부른 스레드에서 바로 돌린다
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
    }
}
