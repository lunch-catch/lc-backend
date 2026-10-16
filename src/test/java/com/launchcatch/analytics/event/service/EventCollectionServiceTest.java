package com.launchcatch.analytics.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.analytics.event.ClientEvent;
import com.launchcatch.analytics.event.EventRecord;
import com.launchcatch.analytics.event.EventType;
import com.launchcatch.analytics.event.EventWriteResult;
import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventCollectionServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final OffsetDateTime OCCURRED_AT = OffsetDateTime.parse("2026-10-01T11:02:10+09:00");

    @Mock
    private EventLogWriter writer;

    private EventCollectionService service;

    @BeforeEach
    void setUp() {
        service = new EventCollectionService(writer);
    }

    @Test
    void 화면_이벤트를_기록용_이벤트로_바꾸고_저장_결과를_반환한다() {
        List<ClientEvent> events = List.of(
                event("0f8b0d6e-3a77-4ee5-9bd3-6af85b495830", "poster_open", 12L, 3L, null),
                event("11e02d39-74e6-4d0d-b9c6-217664713b4a", "map_view", null, null, null),
                event("21ef64c0-5e3f-4b04-a3f5-0740445b35aa", "wishlist_view", null, null, 31L));
        when(writer.write(anyList())).thenReturn(new EventWriteResult(2, 1));

        EventWriteResult result = service.collect(MEMBER_ID, events);

        assertThat(result).isEqualTo(new EventWriteResult(2, 1));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EventRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(writer).write(records.capture());
        assertThat(records.getValue())
                .extracting(EventRecord::eventType)
                .containsExactly(EventType.POSTER_OPEN, EventType.MAP_VIEW, EventType.WISHLIST_VIEW);
        assertThat(records.getValue()).allSatisfy(record -> {
            assertThat(record.memberId()).isEqualTo(MEMBER_ID);
            assertThat(record.occurredAt()).isEqualTo(OCCURRED_AT.toInstant());
        });
    }

    @Test
    void 오십_건까지_받는다() {
        List<ClientEvent> events = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            events.add(event("00000000-0000-0000-0000-" + String.format("%012d", index),
                    "map_view", null, null, null));
        }
        when(writer.write(anyList())).thenReturn(new EventWriteResult(50, 0));

        EventWriteResult result = service.collect(MEMBER_ID, events);

        assertThat(result.accepted()).isEqualTo(50);
        verify(writer).write(anyList());
    }

    @Test
    void 오십한_건은_거절한다() {
        List<ClientEvent> events = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            events.add(event("00000000-0000-0000-0000-" + String.format("%012d", index),
                    "map_view", null, null, null));
        }

        assertInvalidRequest(() -> service.collect(MEMBER_ID, events));
        verifyNoInteractions(writer);
    }

    @Test
    void 지원하지_않는_이벤트_유형은_별도_오류로_거절한다() {
        List<ClientEvent> events = List.of(
                event("0f8b0d6e-3a77-4ee5-9bd3-6af85b495830", "impression", 12L, 3L, null));

        assertThatThrownBy(() -> service.collect(MEMBER_ID, events))
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AnalyticsErrorCode.UNSUPPORTED_EVENT_TYPE));
        verifyNoInteractions(writer);
    }

    @Test
    void 이벤트_유형이_누락되면_잘못된_요청으로_거절한다() {
        List<ClientEvent> events = List.of(
                event("0f8b0d6e-3a77-4ee5-9bd3-6af85b495830", null, null, null, null));

        assertInvalidRequest(() -> service.collect(MEMBER_ID, events));
        verifyNoInteractions(writer);
    }

    @Test
    void 이벤트_식별자가_UUID가_아니면_거절한다() {
        List<ClientEvent> events = List.of(event("not-a-uuid", "map_view", null, null, null));

        assertInvalidRequest(() -> service.collect(MEMBER_ID, events));
        verifyNoInteractions(writer);
    }

    @Test
    void 발생_시각이_누락되면_거절한다() {
        ClientEvent event = new ClientEvent(
                "0f8b0d6e-3a77-4ee5-9bd3-6af85b495830", "map_view", null,
                null, null, null);

        assertInvalidRequest(() -> service.collect(MEMBER_ID, List.of(event)));
        verifyNoInteractions(writer);
    }

    @Test
    void 이벤트_목록이_누락되면_거절한다() {
        assertInvalidRequest(() -> service.collect(MEMBER_ID, null));
        verifyNoInteractions(writer);
    }

    private ClientEvent event(
            String eventId,
            String eventType,
            Long campaignId,
            Long storeId,
            Long wishId
    ) {
        return new ClientEvent(eventId, eventType, OCCURRED_AT, campaignId, storeId, wishId);
    }

    private void assertInvalidRequest(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(AnalyticsException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AnalyticsErrorCode.INVALID_EVENT_REQUEST));
    }
}
