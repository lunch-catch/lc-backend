package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.feed.repository.FeedVisitRepository;
import com.launchcatch.adserving.feed.repository.FeedVisitRepository.Visit;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import com.launchcatch.adserving.feed.repository.ServeLogRepository.Row;
import com.launchcatch.adserving.feed.repository.ServeRecord;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

@DisplayName("서빙 로그 대기열")
class ServeLogBufferTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-01T01:15:30Z");

    private final ServeLogRepository serveLogRepository = mock(ServeLogRepository.class);
    private final FeedVisitRepository feedVisitRepository = mock(FeedVisitRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ServeLogBuffer buffer = new ServeLogBuffer(serveLogRepository, feedVisitRepository, meterRegistry,
            Clock.fixed(NOW, ClockConfig.ZONE));

    private static ServedFeed feedWithCards(long memberId, int cards) {
        List<ServeRecord> records = IntStream.rangeClosed(1, cards)
                .mapToObj(i -> new ServeRecord(UUID.randomUUID(), memberId, 100L + i, 55L, SlotType.RELEVANCE, i, NOW))
                .toList();
        return new ServedFeed(TODAY, memberId, cards == 0 ? null : UUID.randomUUID(), 37.5, 127.0, records);
    }

    @SuppressWarnings("unchecked")
    private List<Row> writtenRows() {
        ArgumentCaptor<List<Row>> captor = ArgumentCaptor.forClass(List.class);
        verify(serveLogRepository).insertAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void 넣은_피드의_카드를_serve_log_로_피드마다_접속_위치를_feed_visit_daily_로_쓴다() {
        ServedFeed feed = feedWithCards(3001L, 2);
        buffer.offer(feed);

        assertThat(buffer.flushOnce()).isTrue();

        assertThat(writtenRows()).extracting(Row::record).containsExactlyElementsOf(feed.cards());
        assertThat(writtenRows()).allSatisfy(r -> {
            assertThat(r.feedId()).isEqualTo(feed.feedId());
            assertThat(r.businessDate()).isEqualTo(TODAY);
        });
        verify(feedVisitRepository).insertAll(List.of(new Visit(TODAY, 3001L, 37.5, 127.0)),
                NOW.atZone(ClockConfig.ZONE).toLocalDateTime());
    }

    @Test
    void 카드가_없는_피드뿐이면_serve_log_는_쓰지_않고_접속_위치만_쓴다() {
        buffer.offer(feedWithCards(3001L, 0));

        buffer.flushOnce();

        verify(serveLogRepository, never()).insertAll(anyList());
        verify(feedVisitRepository).insertAll(anyList(), any());
    }

    @Test
    void 비어_있으면_쓰지_않는다() {
        assertThat(buffer.flushOnce()).isFalse();

        verify(feedVisitRepository, never()).insertAll(anyList(), any());
    }

    @Test
    void 한_번에_50건까지만_꺼내_쓴다() {
        IntStream.range(0, ServeLogBuffer.FEEDS_PER_BATCH + 1).forEach(i -> buffer.offer(feedWithCards(i, 1)));

        buffer.flushOnce();

        assertThat(writtenRows()).hasSize(ServeLogBuffer.FEEDS_PER_BATCH);
        assertThat(buffer.flushOnce()).isTrue();
        assertThat(buffer.flushOnce()).isFalse();
    }

    @Test
    void 대기열이_차면_버리고_그_피드의_카드_수만큼_센다() {
        IntStream.range(0, ServeLogBuffer.CAPACITY).forEach(i -> buffer.offer(feedWithCards(i, 0)));

        buffer.offer(feedWithCards(9999L, 10));
        buffer.offer(feedWithCards(9998L, 0));

        // 카드가 없는 피드도 접속 위치 1건을 잃어 1로 센다
        assertThat(meterRegistry.counter(ServeLogBuffer.DROPPED_METRIC).count()).isEqualTo(11);
    }

    @Test
    void 쓰기가_실패하면_그_묶음을_버리고_세며_다음_묶음은_쓴다() {
        doThrow(new DataAccessResourceFailureException("DB 멈춤")).doNothing()
                .when(serveLogRepository).insertAll(anyList());
        IntStream.range(0, ServeLogBuffer.FEEDS_PER_BATCH).forEach(i -> buffer.offer(feedWithCards(i, 2)));
        buffer.offer(feedWithCards(7777L, 3));

        buffer.flushOnce();
        buffer.flushOnce();

        assertThat(meterRegistry.counter(ServeLogBuffer.FAILED_METRIC).count()).isEqualTo(100);
        verify(serveLogRepository, times(2)).insertAll(anyList());
    }

    @Test
    void 멈출_때_남은_것을_모두_쓴다() {
        buffer.offer(feedWithCards(1L, 1));
        buffer.offer(feedWithCards(2L, 1));

        buffer.stop();

        assertThat(writtenRows()).hasSize(2);
        assertThat(buffer.isRunning()).isFalse();
    }

    @Test
    void 접속_위치_쓰기만_실패하면_서빙_로그는_실패로_세지_않고_접속_위치만_센다() {
        doThrow(new DataAccessResourceFailureException("DB 멈춤")).when(feedVisitRepository).insertAll(anyList(), any());
        buffer.offer(feedWithCards(1L, 3));
        buffer.offer(feedWithCards(2L, 0));

        buffer.flushOnce();

        assertThat(meterRegistry.counter(ServeLogBuffer.FAILED_METRIC).count()).isZero();
        assertThat(meterRegistry.counter(ServeLogBuffer.VISIT_FAILED_METRIC).count()).isEqualTo(2);
    }

    @Test
    void 서빙_로그_쓰기가_실패해도_접속_위치는_쓴다() {
        doThrow(new DataAccessResourceFailureException("DB 멈춤")).when(serveLogRepository).insertAll(anyList());
        buffer.offer(feedWithCards(1L, 3));

        buffer.flushOnce();

        verify(feedVisitRepository).insertAll(anyList(), any());
        assertThat(meterRegistry.counter(ServeLogBuffer.FAILED_METRIC).count()).isEqualTo(3);
    }
}
