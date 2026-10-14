package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.feed.repository.FeedVisitDailyRepository;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import com.launchcatch.adserving.feed.repository.ServeLogRepository.ServeLogRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("서빙 로그 비동기 저장")
class FeedLogWriterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 10, 15, 30);
    private static final ServeLogRow ROW = new ServeLogRow(UUID.randomUUID(), DATE, UUID.randomUUID(), 1L, 101L, 55L,
            SlotType.ALLOCATION, 1, NOW);

    @Mock ServeLogRepository serveLogRepository;
    @Mock FeedVisitDailyRepository feedVisitDailyRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private FeedLogWriter writer(Executor executor) {
        return new FeedLogWriter(executor, serveLogRepository, feedVisitDailyRepository, meterRegistry);
    }

    @Test
    void 서빙_로그와_소수점_3자리로_반올림한_접속_위치를_쓴다() {
        writer(Runnable::run).write(List.of(ROW), DATE, 1L, 37.49795, 127.02749, NOW);

        verify(serveLogRepository).saveAll(List.of(ROW), NOW);
        verify(feedVisitDailyRepository).saveIfAbsent(DATE, 1L, new BigDecimal("37.498"), new BigDecimal("127.027"), NOW);
    }

    @Test
    void 카드가_없으면_접속_위치만_쓴다() {
        writer(Runnable::run).write(List.of(), DATE, 1L, 37.4979, 127.0276, NOW);

        verify(serveLogRepository, never()).saveAll(any(), any());
        verify(feedVisitDailyRepository).saveIfAbsent(DATE, 1L, new BigDecimal("37.498"), new BigDecimal("127.028"), NOW);
    }

    @Test
    void 대기열이_차면_기다리지_않고_버린_행을_지표로_센다_임시() {
        Executor full = task -> {
            throw new RejectedExecutionException("full");
        };

        writer(full).write(List.of(ROW, ROW), DATE, 1L, 37.4979, 127.0276, NOW);

        assertThat(meterRegistry.counter(FeedLogWriter.DROPPED_METRIC).count()).isEqualTo(2.0);
        verifyNoInteractions(serveLogRepository, feedVisitDailyRepository);
    }

    @Test
    void 쓰기가_실패하면_예외를_올리지_않고_실패한_행을_지표로_센다() {
        doThrow(new QueryTimeoutException("db")).when(serveLogRepository).saveAll(any(), any());

        writer(Runnable::run).write(List.of(ROW), DATE, 1L, 37.4979, 127.0276, NOW);

        assertThat(meterRegistry.counter(FeedLogWriter.FAILED_METRIC).count()).isEqualTo(1.0);
    }
}
