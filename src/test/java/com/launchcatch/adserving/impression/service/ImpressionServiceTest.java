package com.launchcatch.adserving.impression.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.contract.ImpressionServedEvent;
import com.launchcatch.adserving.engine.SlotLabels;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.repository.ImpressionCountRepository;
import com.launchcatch.adserving.feed.repository.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.impression.dto.ImpressionRequest;
import com.launchcatch.adserving.impression.dto.ImpressionResponse;
import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLogId;
import com.launchcatch.adserving.impression.entity.ImpressionValidity;
import com.launchcatch.adserving.impression.entity.InvalidReason;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final ImpressionLogRepository impressionLogRepository = mock(ImpressionLogRepository.class);
    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final ImpressionCountRepository impressionCountRepository = mock(ImpressionCountRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ImpressionService service = new ImpressionService(serveRecordRepository, impressionLogRepository,
            adCandidateRepository, impressionCountRepository, meterRegistry, eventPublisher,
            Clock.fixed(NOW, ClockConfig.ZONE));

    private final ImpressionService afterMidnight = new ImpressionService(serveRecordRepository, impressionLogRepository,
            adCandidateRepository, impressionCountRepository, meterRegistry, eventPublisher,
            Clock.fixed(AFTER_MIDNIGHT, ClockConfig.ZONE));

    private void givenLateRecord() {
        when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(
                new ServeRecord(SERVE_ID, MEMBER_ID, 101L, 55L, SlotLabels.ALLOCATION, 1, LATE_SERVED_AT)));
    }

    private static ImpressionRequest request(Long campaignId) {
        return new ImpressionRequest(SERVE_ID.toString(), campaignId,
                OffsetDateTime.parse("2026-10-01T10:15:31.880+09:00"), 37.49794, 127.02765, 2140);
    }

    // 커밋 뒤 동작을 보려고 트랜잭션 동기화를 켠다. 실제 트랜잭션은 통합 테스트가 본다
    @BeforeEach
    void startSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        when(impressionLogRepository.findById(any())).thenReturn(Optional.empty());
        when(impressionLogRepository.insertIgnore(any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private static void rollback() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    private ImpressionLog saved() {
        ArgumentCaptor<ImpressionLog> captor = ArgumentCaptor.forClass(ImpressionLog.class);
        verify(impressionLogRepository).insertIgnore(captor.capture(), any());
        return captor.getValue();
    }

    @Nested
    @DisplayName("판정")
    class Judgement {

        @Test
        void serve_기록이_없으면_요청의_캠페인으로_EXPIRED_OR_UNKNOWN_을_저장하고_차감하지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.empty());

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.INVALID, InvalidReason.EXPIRED_OR_UNKNOWN));
            ImpressionLog log = saved();
            assertThat(log.getCampaignId()).isEqualTo(101L);
            assertThat(log.getStoreId()).isNull();
            assertThat(log.getSlotType()).isNull();
                    }

        @Test
        void 다른_사용자가_받은_카드면_NOT_OWNER_를_저장하고_차감하지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            ImpressionResponse response = service.record(OTHER_MEMBER, request(101L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.INVALID, InvalidReason.NOT_OWNER));
            assertThat(saved().getId()).isEqualTo(ImpressionLogId.of(SERVE_ID, OTHER_MEMBER));
                    }

        @Test
        void 유효하면_serve_기록의_캠페인과_받은_영업일로_저장하고_정산은_부르지_않는다() {
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
                    }

        @Test
        void 요청의_캠페인이_serve_기록과_다르면_serve_기록의_캠페인으로_판정하고_센다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(MEMBER_ID, request(999L));

            assertThat(saved().getCampaignId()).isEqualTo(101L);
                        // 처음 저장된 노출이 커밋된 뒤에만 센다. 롤백되거나 다시 온 요청은 세지 않는다(#783)
            assertThat(meterRegistry.counter(ImpressionService.CAMPAIGN_MISMATCH_METRIC).count()).isZero();
            commit();
            assertThat(meterRegistry.counter(ImpressionService.CAMPAIGN_MISMATCH_METRIC).count()).isEqualTo(1);
        }

        @Test
        void 동시에_와서_먼저_넣은_쪽이_있으면_캠페인이_달라도_세지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));
            when(impressionLogRepository.insertIgnore(any(), any())).thenReturn(false);
            when(impressionLogRepository.findByIdForShare(ImpressionLogId.of(SERVE_ID, MEMBER_ID)))
                    .thenReturn(Optional.of(ImpressionLog.valid(SERVE_ID, MEMBER_ID, SERVED_DATE,
                            new ImpressionLog.Placement(101L, 55L, SlotLabels.ALLOCATION, 1),
                            new ImpressionLog.Sighting(LocalDateTime.of(2026, 10, 1, 10, 15), 37.5, 127.0, null), false)));

            service.record(MEMBER_ID, request(999L));
            commit();

            assertThat(meterRegistry.counter(ImpressionService.CAMPAIGN_MISMATCH_METRIC).count()).isZero();
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
            ImpressionService atMidnight = new ImpressionService(serveRecordRepository, impressionLogRepository,
                    adCandidateRepository, impressionCountRepository, meterRegistry, eventPublisher, ticking);
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            atMidnight.record(MEMBER_ID, request(101L));

            ArgumentCaptor<ImpressionLog> log = ArgumentCaptor.forClass(ImpressionLog.class);
            ArgumentCaptor<LocalDateTime> receivedAt = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(impressionLogRepository).insertIgnore(log.capture(), receivedAt.capture());
            assertThat(receivedAt.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 1, 23, 59, 59, 999_000_000));
            assertThat(log.getValue().getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        }

        @Test
        void 쿠폰이_소진된_뒤의_유효_노출은_sold_out_으로_저장하고_차감하지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));
            when(adCandidateRepository.existsByIdBusinessDateAndIdCampaignIdAndSoldOutAtIsNotNull(SERVED_DATE, 101L))
                    .thenReturn(true);

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response.validity()).isEqualTo(ImpressionValidity.VALID);
            assertThat(saved().isSoldOut()).isTrue();
                    }

        // 정산이 받은 날로 차감한다(#497). 전날 예산은 00:00 에 해제가 끝났다
        @Test
        void 자정을_넘겨_받은_노출은_받은_날로_저장한다() {
            givenLateRecord();

            afterMidnight.record(MEMBER_ID, request(101L));

            assertThat(saved().getBusinessDate()).isEqualTo(RECEIVED_DATE);
                    }

        @Test
        void 자정을_넘겨_받은_노출의_쿠폰_소진은_받은_날의_후보로_본다() {
            givenLateRecord();
            when(adCandidateRepository.existsByIdBusinessDateAndIdCampaignIdAndSoldOutAtIsNotNull(RECEIVED_DATE, 101L))
                    .thenReturn(true);

            afterMidnight.record(MEMBER_ID, request(101L));

            assertThat(saved().isSoldOut()).isTrue();
            assertThat(saved().needsDispatch()).isFalse();
                    }

        @Test
        void 받은_영업일과_좌표_3자리_반올림과_한국_시각의_표시_시각으로_저장한다() {
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

        @Test
        void 이미_저장된_키면_처음_판정을_돌려주고_저장도_차감도_하지_않는다() {
            ImpressionLog first = ImpressionLog.unknown(SERVE_ID, MEMBER_ID, SERVED_DATE, 101L,
                    new ImpressionLog.Sighting(LocalDateTime.of(2026, 10, 1, 10, 0), 37.5, 127.0, null));
            when(impressionLogRepository.findById(ImpressionLogId.of(SERVE_ID, MEMBER_ID))).thenReturn(Optional.of(first));

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.INVALID, InvalidReason.EXPIRED_OR_UNKNOWN));
            verify(impressionLogRepository, never()).insertIgnore(any(), any());
            verifyNoInteractions(serveRecordRepository);
        }

        @Test
        void 동시에_와서_먼저_넣은_쪽이_있으면_그_판정을_돌려주고_차감하지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));
            when(impressionLogRepository.insertIgnore(any(), any())).thenReturn(false);
            ImpressionLog winner = ImpressionLog.valid(SERVE_ID, MEMBER_ID, SERVED_DATE,
                    new ImpressionLog.Placement(101L, 55L, SlotLabels.ALLOCATION, 1),
                    new ImpressionLog.Sighting(LocalDateTime.of(2026, 10, 1, 10, 15), 37.5, 127.0, null), false);
            when(impressionLogRepository.findById(ImpressionLogId.of(SERVE_ID, MEMBER_ID))).thenReturn(Optional.empty());
            // 먼저 넣은 쪽은 이 트랜잭션의 스냅샷 뒤에 커밋돼 잠금 읽기로만 보인다(#781)
            when(impressionLogRepository.findByIdForShare(ImpressionLogId.of(SERVE_ID, MEMBER_ID))).thenReturn(Optional.of(winner));

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.VALID, null));
                        commit();
            verifyNoInteractions(impressionCountRepository);
        }
    }

    @Nested
    @DisplayName("유효 노출 수 카운터")
    class Counter {

        @Test
        void 처음_저장된_유효_노출은_커밋된_뒤에_받은_영업일의_카운터를_올린다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(MEMBER_ID, request(101L));

            verifyNoInteractions(impressionCountRepository);
            commit();
            verify(impressionCountRepository).increment(SERVED_DATE, 101L);
        }

        @Test
        void 자정을_넘겨_받은_노출은_받은_날의_카운터를_올린다() {
            givenLateRecord();

            afterMidnight.record(MEMBER_ID, request(101L));
            commit();

            verify(impressionCountRepository).increment(RECEIVED_DATE, 101L);
        }

        @Test
        void 롤백되면_올리지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(MEMBER_ID, request(101L));
            rollback();

            verifyNoInteractions(impressionCountRepository);
        }

        @Test
        void 쿠폰이_소진된_뒤의_유효_노출도_센다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));
            when(adCandidateRepository.existsByIdBusinessDateAndIdCampaignIdAndSoldOutAtIsNotNull(SERVED_DATE, 101L))
                    .thenReturn(true);

            service.record(MEMBER_ID, request(101L));
            commit();

            verify(impressionCountRepository).increment(SERVED_DATE, 101L);
        }

        @Test
        void 무효_노출은_세지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(OTHER_MEMBER, request(101L));
            commit();

            verifyNoInteractions(impressionCountRepository);
        }
    }

    @Nested
    @DisplayName("분석 이벤트")
    class FunnelEvent {

        @Test
        void 처음_저장된_유효_노출은_serve_기록의_캠페인과_가게와_한국_시각의_표시_시각으로_한_번_발행한다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(MEMBER_ID, request(999L));

            verify(eventPublisher).publishEvent(new ImpressionServedEvent("impression:" + SERVE_ID,
                    LocalDateTime.of(2026, 10, 1, 10, 15, 31, 880_000_000), MEMBER_ID, 101L, 55L, SERVE_ID));
        }

        @Test
        void 쿠폰이_소진된_뒤의_유효_노출도_발행한다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));
            when(adCandidateRepository.existsByIdBusinessDateAndIdCampaignIdAndSoldOutAtIsNotNull(SERVED_DATE, 101L))
                    .thenReturn(true);

            service.record(MEMBER_ID, request(101L));

            verify(eventPublisher).publishEvent(any(ImpressionServedEvent.class));
        }

        @Test
        void 무효_노출은_발행하지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(OTHER_MEMBER, request(101L));

            verifyNoInteractions(eventPublisher);
        }

        @Test
        void 이미_저장된_키면_발행하지_않는다() {
            ImpressionLog first = ImpressionLog.valid(SERVE_ID, MEMBER_ID, SERVED_DATE,
                    new ImpressionLog.Placement(101L, 55L, SlotLabels.ALLOCATION, 1),
                    new ImpressionLog.Sighting(LocalDateTime.of(2026, 10, 1, 10, 15), 37.5, 127.0, null), false);
            when(impressionLogRepository.findById(ImpressionLogId.of(SERVE_ID, MEMBER_ID))).thenReturn(Optional.of(first));

            service.record(MEMBER_ID, request(101L));

            verifyNoInteractions(eventPublisher);
        }

        @Test
        void 동시에_와서_먼저_넣은_쪽이_있으면_발행하지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));
            when(impressionLogRepository.insertIgnore(any(), any())).thenReturn(false);
            when(impressionLogRepository.findById(ImpressionLogId.of(SERVE_ID, MEMBER_ID))).thenReturn(Optional.empty());
            when(impressionLogRepository.findByIdForShare(ImpressionLogId.of(SERVE_ID, MEMBER_ID)))
                    .thenReturn(Optional.of(ImpressionLog.valid(SERVE_ID, MEMBER_ID, SERVED_DATE,
                            new ImpressionLog.Placement(101L, 55L, SlotLabels.ALLOCATION, 1),
                            new ImpressionLog.Sighting(LocalDateTime.of(2026, 10, 1, 10, 15), 37.5, 127.0, null), false)));

            service.record(MEMBER_ID, request(101L));

            verifyNoInteractions(eventPublisher);
        }
    }
}
