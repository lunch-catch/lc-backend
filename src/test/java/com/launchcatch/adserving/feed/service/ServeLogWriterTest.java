package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class ServeLogWriterTest {

    private static final Long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 15, 31);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T01:15:31Z"), ClockConfig.ZONE);
    private static final BigDecimal LAT = new BigDecimal("37.498");
    private static final BigDecimal LNG = new BigDecimal("127.028");
    private static final List<ServeRecord> RECORDS = List.of(record(1), record(2));

    @Mock ServeLogRepository serveLogRepository;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void 서빙_로그와_그날_접속_위치를_실행기에서_쓴다() {
        writer(Runnable::run).write(MEMBER_ID, TODAY, RECORDS, LAT, LNG);

        verify(serveLogRepository).saveAll(RECORDS, NOW);
        verify(serveLogRepository).insertVisitIfAbsent(TODAY, MEMBER_ID, LAT, LNG, NOW);
    }

    @Test
    void 카드가_없으면_서빙_로그는_건너뛰고_접속_위치만_쓴다() {
        writer(Runnable::run).write(MEMBER_ID, TODAY, List.of(), LAT, LNG);

        verify(serveLogRepository, never()).saveAll(any(), any());
        verify(serveLogRepository).insertVisitIfAbsent(TODAY, MEMBER_ID, LAT, LNG, NOW);
    }

    @Test
    void 대기열이_차면_버리고_버린_카드_수를_센다() {
        Executor full = task -> {
            throw new RejectedExecutionException("full");
        };

        writer(full).write(MEMBER_ID, TODAY, RECORDS, LAT, LNG);

        verify(serveLogRepository, never()).saveAll(any(), any());
        assertThat(dropped("queue_full")).isEqualTo(2.0);
    }

    @Test
    void 쓰기가_실패하면_버리고_버린_카드_수를_센다() {
        doThrow(new DataAccessResourceFailureException("down")).when(serveLogRepository).saveAll(RECORDS, NOW);

        writer(Runnable::run).write(MEMBER_ID, TODAY, RECORDS, LAT, LNG);

        assertThat(dropped("write_failed")).isEqualTo(2.0);
    }

    @Test
    void 내려갈_때_대기열에_남은_로그를_마저_쓴다() throws InterruptedException {
        ServeLogWriter writer = new ServeLogWriter(serveLogRepository, meterRegistry, CLOCK);

        writer.write(MEMBER_ID, TODAY, RECORDS, LAT, LNG);
        writer.shutdown();

        verify(serveLogRepository).saveAll(RECORDS, NOW);
    }

    private ServeLogWriter writer(Executor executor) {
        return new ServeLogWriter(executor, serveLogRepository, meterRegistry, CLOCK);
    }

    private double dropped(String reason) {
        return meterRegistry.counter(ServeLogWriter.DROPPED_METRIC, "reason", reason).count();
    }

    private static ServeRecord record(int position) {
        return new ServeRecord(UUID.randomUUID(), UUID.randomUUID(), MEMBER_ID, TODAY, 100L + position, 55L,
                SlotType.RELEVANCE, position, NOW);
    }
}
