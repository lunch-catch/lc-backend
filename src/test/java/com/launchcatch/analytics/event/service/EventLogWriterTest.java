package com.launchcatch.analytics.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.event.EventRecord;
import com.launchcatch.analytics.event.EventType;
import com.launchcatch.analytics.event.EventWriteResult;
import com.launchcatch.analytics.event.entity.EventLogEntry;
import com.launchcatch.analytics.event.repository.EventLogRepository;
import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class EventLogWriterTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-01T02:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0f8b0d6e-3a77-4ee5-9bd3-6af85b495830");

    @Mock
    private EventLogRepository repository;

    private EventLogWriter writer;

    @BeforeEach
    void setUp() {
        writer = new EventLogWriter(repository, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }

    @Test
    void 미래_발생_시각을_수신_시각으로_보정하고_KST로_저장한다() {
        when(repository.insert(any())).thenReturn(true);
        EventRecord record = screenEvent(EventType.POSTER_OPEN, RECEIVED_AT.plusSeconds(1), 12L, 3L, null);

        EventWriteResult result = writer.write(List.of(record));

        assertThat(result).isEqualTo(new EventWriteResult(1, 0));
        ArgumentCaptor<EventLogEntry> entry = ArgumentCaptor.forClass(EventLogEntry.class);
        verify(repository).insert(entry.capture());
        assertThat(entry.getValue().occurredAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 11, 0));
        assertThat(entry.getValue().createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 11, 0));
        assertThat(entry.getValue().updatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 11, 0));
    }

    @Test
    void 같은_이벤트_식별자가_있으면_중복으로_센다() {
        when(repository.insert(any())).thenReturn(false);

        EventWriteResult result = writer.write(screenEvent(
                EventType.MAP_VIEW, RECEIVED_AT.minusSeconds(1), null, null, null));

        assertThat(result).isEqualTo(new EventWriteResult(0, 1));
    }

    @Test
    void 목록의_모든_식별자_조합을_검증한_뒤에_저장한다() {
        EventRecord valid = screenEvent(EventType.MAP_VIEW, RECEIVED_AT, null, null, null);
        EventRecord invalid = screenEvent(EventType.POSTER_OPEN, RECEIVED_AT, 12L, null, null);

        assertInvalidRequest(() -> writer.write(List.of(valid, invalid)));
        verifyNoInteractions(repository);
    }

    @Test
    void 지도_조회에_대상_식별자가_있으면_거절한다() {
        EventRecord record = screenEvent(EventType.MAP_VIEW, RECEIVED_AT, 12L, 3L, null);

        assertInvalidRequest(() -> writer.write(record));
        verifyNoInteractions(repository);
    }

    @Test
    void 찜_목록_조회에는_찜_식별자를_둘_수_있다() {
        when(repository.insert(any())).thenReturn(true);
        EventRecord record = screenEvent(EventType.WISHLIST_VIEW, RECEIVED_AT, null, null, 31L);

        EventWriteResult result = writer.write(record);

        assertThat(result).isEqualTo(new EventWriteResult(1, 0));
    }

    @Test
    void 쿠폰_발급_열기에는_회원과_대상_식별자가_없고_발급_회차가_필수다() {
        when(repository.insert(any())).thenReturn(true);
        EventRecord record = new EventRecord(
                EVENT_ID, EventType.ISSUE_OPEN, RECEIVED_AT, null, null, null,
                null, null, 41L, null);

        EventWriteResult result = writer.write(record);

        assertThat(result.accepted()).isEqualTo(1);
    }

    @Test
    void UNIQUE와_무관한_저장_실패는_전파한다() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db unavailable");
        when(repository.insert(any())).thenThrow(failure);

        assertThatThrownBy(() -> writer.write(
                screenEvent(EventType.MAP_VIEW, RECEIVED_AT, null, null, null)))
                .isSameAs(failure);
    }

    private EventRecord screenEvent(
            EventType type,
            Instant occurredAt,
            Long campaignId,
            Long storeId,
            Long wishId
    ) {
        return new EventRecord(
                EVENT_ID, type, occurredAt, 7L, campaignId, storeId,
                null, wishId, null, null);
    }

    private void assertInvalidRequest(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AnalyticsErrorCode.INVALID_EVENT_REQUEST));
    }
}
