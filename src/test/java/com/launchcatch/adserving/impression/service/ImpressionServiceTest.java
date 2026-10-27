package com.launchcatch.adserving.impression.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.SlotLabels;
import com.launchcatch.adserving.engine.service.CandidateCache;
import com.launchcatch.adserving.feed.repository.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.impression.dto.ImpressionRequest;
import com.launchcatch.adserving.impression.dto.ImpressionResponse;
import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLogId;
import com.launchcatch.adserving.impression.entity.ImpressionValidity;
import com.launchcatch.adserving.impression.entity.InvalidReason;
import com.launchcatch.global.config.ClockConfig;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("노출 수집 서비스")
class ImpressionServiceTest {

    private static final Long MEMBER_ID = 3001L;
    private static final Long OTHER_MEMBER = 3002L;
    private static final UUID SERVE_ID = UUID.fromString("01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0");
    private static final LocalDate SERVED_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDate RECEIVED_DATE = LocalDate.of(2026, 10, 2);
    // 23:50 에 내려준 카드를 자정을 넘겨 00:10 에 받는다
    private static final Instant LATE_SERVED_AT = LocalDateTime.of(2026, 10, 1, 23, 50).atZone(ClockConfig.ZONE).toInstant();
    private static final Instant AFTER_MIDNIGHT = LocalDateTime.of(2026, 10, 2, 0, 10).atZone(ClockConfig.ZONE).toInstant();
    private static final Instant SERVED_AT = LocalDateTime.of(2026, 10, 1, 10, 15, 30).atZone(ClockConfig.ZONE).toInstant();
    private static final Instant NOW = LocalDateTime.of(2026, 10, 1, 10, 15, 32).atZone(ClockConfig.ZONE).toInstant();
    private static final ServeRecord RECORD = new ServeRecord(SERVE_ID, MEMBER_ID, 101L, 55L, SlotLabels.ALLOCATION, 1, SERVED_AT);

    private final ServeRecordRepository serveRecordRepository = mock(ServeRecordRepository.class);
    private final CandidateCache candidateCache = mock(CandidateCache.class);
    private final ImpressionWriteBuffer writeBuffer = mock(ImpressionWriteBuffer.class);
    private final ImpressionService service = new ImpressionService(serveRecordRepository,
            candidateCache, writeBuffer, Clock.fixed(NOW, ClockConfig.ZONE));

    private final ImpressionService afterMidnight = new ImpressionService(serveRecordRepository,
            candidateCache, writeBuffer, Clock.fixed(AFTER_MIDNIGHT, ClockConfig.ZONE));

    private void givenLateRecord() {
        when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(
                new ServeRecord(SERVE_ID, MEMBER_ID, 101L, 55L, SlotLabels.ALLOCATION, 1, LATE_SERVED_AT)));
    }

    private static ImpressionRequest request(Long campaignId) {
        return new ImpressionRequest(SERVE_ID.toString(), campaignId,
                OffsetDateTime.parse("2026-10-01T10:15:31.880+09:00"), 37.49794, 127.02765, 2140);
    }

    private QueuedImpression queued() {
        ArgumentCaptor<QueuedImpression> captor = ArgumentCaptor.forClass(QueuedImpression.class);
        verify(writeBuffer).offer(captor.capture());
        return captor.getValue();
    }

    private ImpressionLog saved() {
        return queued().log();
    }

    @Nested
    @DisplayName("판정")
    class Judgement {

        @Test
        void serve_기록이_없으면_요청의_캠페인으로_EXPIRED_OR_UNKNOWN_을_큐에_넣는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.empty());

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.INVALID, InvalidReason.EXPIRED_OR_UNKNOWN));
            ImpressionLog log = saved();
            assertThat(log.getCampaignId()).isEqualTo(101L);
            assertThat(log.getStoreId()).isNull();
            assertThat(log.getSlotType()).isNull();
            assertThat(queued().campaignMismatch()).isFalse();
        }

        @Test
        void 다른_사용자가_받은_카드면_NOT_OWNER_를_큐에_넣는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            ImpressionResponse response = service.record(OTHER_MEMBER, request(999L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.INVALID, InvalidReason.NOT_OWNER));
            assertThat(saved().getId()).isEqualTo(ImpressionLogId.of(SERVE_ID, OTHER_MEMBER));
            // 불일치는 유효 노출에서만 센다
            assertThat(queued().campaignMismatch()).isFalse();
        }

        @Test
        void 유효하면_serve_기록의_캠페인과_받은_영업일로_큐에_넣는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.VALID, null));
            ImpressionLog log = saved();
            assertThat(log.getCampaignId()).isEqualTo(101L);
            assertThat(log.getStoreId()).isEqualTo(55L);
            assertThat(log.getSlotType()).isEqualTo(SlotLabels.ALLOCATION);
            assertThat(log.getSlotPosition()).isEqualTo(1);
            assertThat(log.isSoldOut()).isFalse();
            assertThat(log.needsDispatch()).isTrue();
            assertThat(queued().campaignMismatch()).isFalse();
        }

        @Test
        void 요청의_캠페인이_serve_기록과_다르면_serve_기록의_캠페인으로_판정하고_불일치를_표시한다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(MEMBER_ID, request(999L));

            assertThat(saved().getCampaignId()).isEqualTo(101L);
            assertThat(queued().campaignMismatch()).isTrue();
        }

        /*
         * 자정 직전에 받아 처리 중에 자정을 넘긴다. 받은 시각을 한 번만 읽어 영업일과 저장 시각(created_at)을 같은 날로 둔다.
         * 따로 읽으면 전날 노출이 전날의 0시 구간으로 집계된다(#783).
         */
        @Test
        void 받은_시각을_한_번만_읽어_영업일과_저장_시각이_같은_날이다() {
            Instant justBefore = LocalDateTime.of(2026, 10, 1, 23, 59, 59, 999_000_000).atZone(ClockConfig.ZONE).toInstant();
            Clock ticking = new Clock() {
                private Instant next = justBefore;

                @Override
                public ZoneId getZone() {
                    return ClockConfig.ZONE;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    Instant now = next;
                    next = next.plusMillis(5);
                    return now;
                }
            };
            ImpressionService atMidnight = new ImpressionService(serveRecordRepository,
                    candidateCache, writeBuffer, ticking);
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            atMidnight.record(MEMBER_ID, request(101L));

            assertThat(queued().receivedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 23, 59, 59, 999_000_000));
            assertThat(saved().getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        }

        // 저장 시각은 DATETIME(6) 이라 마이크로초로 자른다. 자르지 않으면 묶음 저장이 새로 넣은 행을 가리지 못한다
        @Test
        void 받은_시각을_마이크로초로_자른다() {
            Instant withNanos = NOW.plusNanos(123_456_789);
            ImpressionService nanos = new ImpressionService(serveRecordRepository,
                    candidateCache, writeBuffer, Clock.fixed(withNanos, ClockConfig.ZONE));
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            nanos.record(MEMBER_ID, request(101L));

            assertThat(queued().receivedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 10, 15, 32, 123_456_000));
        }

        @Test
        void 쿠폰이_소진된_뒤의_유효_노출은_sold_out_으로_큐에_넣는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));
            when(candidateCache.isSoldOut(SERVED_DATE, 101L))
                    .thenReturn(true);

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response.validity()).isEqualTo(ImpressionValidity.VALID);
            assertThat(saved().isSoldOut()).isTrue();
        }

        // 정산이 받은 날로 차감한다(#497). 전날 예산은 00:00 에 해제가 끝났다
        @Test
        void 자정을_넘겨_받은_노출은_받은_날로_큐에_넣는다() {
            givenLateRecord();

            afterMidnight.record(MEMBER_ID, request(101L));

            assertThat(saved().getBusinessDate()).isEqualTo(RECEIVED_DATE);
        }

        @Test
        void 자정을_넘겨_받은_노출의_쿠폰_소진은_받은_날의_후보로_본다() {
            givenLateRecord();
            when(candidateCache.isSoldOut(RECEIVED_DATE, 101L))
                    .thenReturn(true);

            afterMidnight.record(MEMBER_ID, request(101L));

            assertThat(saved().isSoldOut()).isTrue();
            assertThat(saved().needsDispatch()).isFalse();
        }

        @Test
        void 받은_영업일과_좌표_3자리_반올림과_한국_시각의_표시_시각으로_큐에_넣는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(MEMBER_ID, request(101L));

            ImpressionLog log = saved();
            assertThat(log.getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(log.getLatRounded()).isEqualTo(new BigDecimal("37.498"));
            assertThat(log.getLngRounded()).isEqualTo(new BigDecimal("127.028"));
            assertThat(log.getDisplayedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 10, 15, 31, 880_000_000));
            assertThat(log.getViewedMs()).isEqualTo(2140);
        }
    }

    @Nested
    @DisplayName("같은 노출이 다시 올 때")
    class Duplicate {

        /*
         * 저장된 노출을 DB 에서 확인하지 않고 늘 다시 판정해 큐에 넣는다(#1044). 클라이언트는 응답을 쓰지 않는다.
         * 저장은 INSERT IGNORE 라 처음 것만 남고 카운터와 이벤트도 처음 저장에서만 나간다.
         */
        @Test
        void 다시_보내면_DB_를_읽지_않고_다시_판정해_큐에_넣는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(MEMBER_ID, request(101L));
            ImpressionResponse again = service.record(MEMBER_ID, request(101L));

            assertThat(again).isEqualTo(new ImpressionResponse(ImpressionValidity.VALID, null));
            verify(writeBuffer, times(2)).offer(any());
        }
    }
}
