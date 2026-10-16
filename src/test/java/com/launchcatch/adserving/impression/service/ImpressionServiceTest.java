package com.launchcatch.adserving.impression.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.repository.ImpressionCountRepository;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.impression.dto.ImpressionRequest;
import com.launchcatch.adserving.impression.dto.ImpressionResponse;
import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLogId;
import com.launchcatch.adserving.impression.entity.ImpressionValidity;
import com.launchcatch.adserving.impression.entity.InvalidReason;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository;
import com.launchcatch.billing.contract.PointDeductor;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("노출 수집 서비스")
class ImpressionServiceTest {

    private static final Long MEMBER_ID = 3001L;
    private static final Long OTHER_MEMBER = 3002L;
    private static final UUID SERVE_ID = UUID.fromString("01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0");
    private static final LocalDate SERVED_DATE = LocalDate.of(2026, 10, 1);
    private static final Instant SERVED_AT = LocalDateTime.of(2026, 10, 1, 10, 15, 30).atZone(ClockConfig.ZONE).toInstant();
    private static final Instant NOW = LocalDateTime.of(2026, 10, 1, 10, 15, 32).atZone(ClockConfig.ZONE).toInstant();
    private static final ServeRecord RECORD = new ServeRecord(SERVE_ID, MEMBER_ID, 101L, 55L, SlotType.ALLOCATION, 1, SERVED_AT);

    private final ServeRecordRepository serveRecordRepository = mock(ServeRecordRepository.class);
    private final ImpressionLogRepository impressionLogRepository = mock(ImpressionLogRepository.class);
    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final ImpressionCountRepository impressionCountRepository = mock(ImpressionCountRepository.class);
    private final PointDeductor pointDeductor = mock(PointDeductor.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ImpressionService service = new ImpressionService(serveRecordRepository, impressionLogRepository,
            adCandidateRepository, impressionCountRepository, pointDeductor, meterRegistry,
            Clock.fixed(NOW, ClockConfig.ZONE));

    private static ImpressionRequest request(Long campaignId) {
        return new ImpressionRequest(SERVE_ID.toString(), campaignId,
                OffsetDateTime.parse("2026-10-01T10:15:31.880+09:00"), 37.49794, 127.02765, 2140);
    }

    // 커밋 뒤 동작을 보려고 트랜잭션 동기화를 켠다. 실제 트랜잭션은 통합 테스트가 본다
    @BeforeEach
    void startSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        when(impressionLogRepository.findById(any())).thenReturn(Optional.empty());
        when(impressionLogRepository.insertIgnore(any())).thenReturn(true);
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
        verify(impressionLogRepository).insertIgnore(captor.capture());
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
            verifyNoInteractions(pointDeductor);
        }

        @Test
        void 다른_사용자가_받은_카드면_NOT_OWNER_를_저장하고_차감하지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            ImpressionResponse response = service.record(OTHER_MEMBER, request(101L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.INVALID, InvalidReason.NOT_OWNER));
            assertThat(saved().getId()).isEqualTo(ImpressionLogId.of(SERVE_ID, OTHER_MEMBER));
            verifyNoInteractions(pointDeductor);
        }

        @Test
        void 유효하면_serve_기록의_캠페인과_서빙한_영업일로_차감한다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.VALID, null));
            ImpressionLog log = saved();
            assertThat(log.getCampaignId()).isEqualTo(101L);
            assertThat(log.getStoreId()).isEqualTo(55L);
            assertThat(log.getSlotType()).isEqualTo(SlotType.ALLOCATION);
            assertThat(log.getSlotPosition()).isEqualTo(1);
            assertThat(log.isSoldOut()).isFalse();
            verify(pointDeductor).deduct(SERVE_ID, 101L, SERVED_DATE);
        }

        @Test
        void 요청의_캠페인이_serve_기록과_다르면_serve_기록의_캠페인으로_판정하고_센다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(MEMBER_ID, request(999L));

            assertThat(saved().getCampaignId()).isEqualTo(101L);
            verify(pointDeductor).deduct(SERVE_ID, 101L, SERVED_DATE);
            assertThat(meterRegistry.counter(ImpressionService.CAMPAIGN_MISMATCH_METRIC).count()).isEqualTo(1);
        }

        @Test
        void 쿠폰이_소진된_뒤의_유효_노출은_sold_out_으로_저장하고_차감하지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));
            when(adCandidateRepository.existsByIdBusinessDateAndIdCampaignIdAndSoldOutAtIsNotNull(SERVED_DATE, 101L))
                    .thenReturn(true);

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response.validity()).isEqualTo(ImpressionValidity.VALID);
            assertThat(saved().isSoldOut()).isTrue();
            verifyNoInteractions(pointDeductor);
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
            verify(impressionLogRepository, never()).insertIgnore(any());
            verifyNoInteractions(pointDeductor, serveRecordRepository);
        }

        @Test
        void 동시에_와서_먼저_넣은_쪽이_있으면_그_판정을_돌려주고_차감하지_않는다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));
            when(impressionLogRepository.insertIgnore(any())).thenReturn(false);
            ImpressionLog winner = ImpressionLog.valid(MEMBER_ID, SERVED_DATE, RECORD,
                    new ImpressionLog.Sighting(LocalDateTime.of(2026, 10, 1, 10, 15), 37.5, 127.0, null), false);
            when(impressionLogRepository.findById(ImpressionLogId.of(SERVE_ID, MEMBER_ID)))
                    .thenReturn(Optional.empty(), Optional.of(winner));

            ImpressionResponse response = service.record(MEMBER_ID, request(101L));

            assertThat(response).isEqualTo(new ImpressionResponse(ImpressionValidity.VALID, null));
            verifyNoInteractions(pointDeductor);
            commit();
            verifyNoInteractions(impressionCountRepository);
        }
    }

    @Nested
    @DisplayName("유효 노출 수 카운터")
    class Counter {

        @Test
        void 처음_저장된_유효_노출은_커밋된_뒤에_서빙한_영업일의_카운터를_올린다() {
            when(serveRecordRepository.findById(SERVE_ID)).thenReturn(Optional.of(RECORD));

            service.record(MEMBER_ID, request(101L));

            verifyNoInteractions(impressionCountRepository);
            commit();
            verify(impressionCountRepository).increment(SERVED_DATE, 101L);
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
}
