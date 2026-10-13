package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.feed.ServeLogQueue;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.repository.FeedVisitDailyRepository;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import com.launchcatch.adserving.feed.repository.ServeRecordCacheRepository;
import com.launchcatch.adserving.feed.repository.ServedHistoryRepository;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("serve 기록")
class ServeRecordServiceTest {

    private static final Long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime SERVED_AT = LocalDateTime.of(2026, 10, 1, 10, 15, 30);
    private static final Instant NOW = Instant.parse("2026-10-01T01:15:31Z");

    @Mock ServeRecordCacheRepository serveRecordCacheRepository;
    @Mock ServedHistoryRepository servedHistoryRepository;
    @Mock ServeLogRepository serveLogRepository;
    @Mock FeedVisitDailyRepository feedVisitDailyRepository;
    @Mock ServeLogQueue serveLogQueue;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<Runnable> queued = new ArrayList<>();
    private ServeRecordService service;

    @BeforeEach
    void setUp() {
        service = new ServeRecordService(serveRecordCacheRepository, servedHistoryRepository, serveLogRepository,
                feedVisitDailyRepository, serveLogQueue, Clock.fixed(NOW, ClockConfig.ZONE), meterRegistry);
        when(serveLogQueue.offer(any())).thenAnswer(invocation -> queued.add(invocation.getArgument(0)));
    }

    @Test
    void serve_기록과_내려준_이력은_바로_쓰고_서빙_로그는_대기열에서_쓴다() {
        List<ServeRecord> records = List.of(record(101), record(102));

        service.record(records);

        verify(serveRecordCacheRepository).saveAll(records);
        verify(servedHistoryRepository).record(MEMBER_ID, TODAY, List.of(101L, 102L));
        verify(serveLogRepository, never()).saveAll(anyList(), any());
        runQueued();
        verify(serveLogRepository).saveAll(records, LocalDateTime.ofInstant(NOW, ClockConfig.ZONE));
    }

    @Test
    void 내려준_카드_수를_센다() {
        service.record(List.of(record(101), record(102)));

        assertThat(meterRegistry.counter(ServeRecordService.SERVED_CARDS_METRIC).count()).isEqualTo(2.0);
    }

    @Test
    void 캐시가_멈추면_피드를_막지_않고_센다() {
        doThrow(new QueryTimeoutException("down")).when(serveRecordCacheRepository).saveAll(anyList());
        doThrow(new QueryTimeoutException("down")).when(servedHistoryRepository).record(any(), any(), anyList());

        service.record(List.of(record(101)));

        assertThat(meterRegistry.counter(FeedService.CACHE_BYPASS_METRIC, "target", "serve_record").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(FeedService.CACHE_BYPASS_METRIC, "target", "served_history").count()).isEqualTo(1.0);
        assertThat(queued).hasSize(1);
    }

    @Test
    void 대기열이_차면_서빙_로그를_버리고_센다() {
        when(serveLogQueue.offer(any())).thenReturn(false);

        service.record(List.of(record(101), record(102)));

        assertThat(dropped("serve_log", "queue_full")).isEqualTo(2.0);
    }

    @Test
    void 서빙_로그_쓰기가_실패하면_버리고_센다() {
        doThrow(new DataAccessResourceFailureException("db down")).when(serveLogRepository).saveAll(anyList(), any());
        service.record(List.of(record(101)));

        runQueued();

        assertThat(dropped("serve_log", "write_failed")).isEqualTo(1.0);
    }

    @Test
    void 접속_위치는_대기열에서_쓴다() {
        service.recordVisit(MEMBER_ID, TODAY, 37.4979, 127.0276);

        verify(feedVisitDailyRepository, never()).saveFirst(any(), any(), anyDouble(), anyDouble(), any());
        runQueued();
        verify(feedVisitDailyRepository).saveFirst(eq(TODAY), eq(MEMBER_ID), eq(37.4979), eq(127.0276), any());
    }

    @Test
    void 대기열이_차면_접속_위치를_버리고_센다() {
        when(serveLogQueue.offer(any())).thenReturn(false);

        service.recordVisit(MEMBER_ID, TODAY, 37.4979, 127.0276);

        assertThat(dropped("feed_visit_daily", "queue_full")).isEqualTo(1.0);
    }

    private void runQueued() {
        queued.forEach(Runnable::run);
    }

    private double dropped(String table, String reason) {
        return meterRegistry.counter(ServeRecordService.DROPPED_METRIC, "table", table, "reason", reason).count();
    }

    private static ServeRecord record(long campaignId) {
        return new ServeRecord(UUID.randomUUID(), UUID.randomUUID(), MEMBER_ID, TODAY, campaignId, 55,
                SlotType.ALLOCATION, 1, SERVED_AT);
    }
}
