package com.launchcatch.adserving.impression.service;

import com.launchcatch.adserving.contract.ImpressionServedEvent;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.repository.ImpressionCountRepository;
import com.launchcatch.adserving.feed.repository.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.impression.dto.ImpressionRequest;
import com.launchcatch.adserving.impression.dto.ImpressionResponse;
import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLog.Placement;
import com.launchcatch.adserving.impression.entity.ImpressionLog.Sighting;
import com.launchcatch.adserving.impression.entity.ImpressionLogId;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/*
 * 카드가 보인 순간 1건을 받아 유효와 무효를 가르고 저장한다(FR-066, FR-097).
 * 정산을 부르지 않는다. 커밋된 유효 노출은 ImpressionDispatchService 가 모아 정산에 넘긴다(노출 수집 v2, #1010).
 */
@Service
@RequiredArgsConstructor
public class ImpressionService {

    // 요청의 캠페인이 serve 기록과 달랐던 횟수다. 판정은 serve 기록으로 한다(#323).
    static final String CAMPAIGN_MISMATCH_METRIC = "adserving_impression_campaign_mismatch_total";

    /*
     * 같은 노출이 다시 처리돼도 분석이 하나로 거르게 serveId 로 만든다.
     * 유효 노출은 serve 의 주인 것뿐이라 사용자를 넣지 않는다.
     */
    private static final String EVENT_ID_PREFIX = "impression:";

    private final ServeRecordRepository serveRecordRepository;
    private final ImpressionLogRepository impressionLogRepository;
    private final AdCandidateRepository adCandidateRepository;
    private final ImpressionCountRepository impressionCountRepository;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /*
     * 같은 사용자가 같은 카드를 다시 보내면 새로 기록하지 않고 처음 판정을 돌려준다.
     * 동시에 두 건이 와도 INSERT IGNORE 가 한 건만 넣는다. 늦은 쪽은 먼저 넣은 쪽의 판정을 돌려주고 차감하지 않는다.
     */
    @Transactional
    public ImpressionResponse record(Long memberId, ImpressionRequest request) {
        UUID serveId = UUID.fromString(request.serveId());
        ImpressionLogId id = ImpressionLogId.of(serveId, memberId);
        Optional<ImpressionLog> existing = impressionLogRepository.findById(id);
        if (existing.isPresent()) {
            return ImpressionResponse.of(existing.get());
        }

        /*
         * 판정, 소진 확인, 카운터가 같은 날을 보게 받은 시각을 한 번만 읽는다. 릴레이도 이 날로 정산에 넘긴다(PointDeductor 계약).
         * 저장 시각(created_at)도 이 값이다. 따로 읽으면 자정 직전 노출이 전날의 0시 구간으로 집계된다(#783).
         */
        LocalDateTime receivedAt = LocalDateTime.now(clock);
        LocalDate receivedDate = receivedAt.toLocalDate();
        Optional<ServeRecord> found = serveRecordRepository.findById(serveId);
        ImpressionLog log = judge(memberId, serveId, receivedDate, request, found);
        if (!impressionLogRepository.insertIgnore(log, receivedAt)) {
            /*
             * 먼저 넣은 쪽이 위 중복 확인 뒤에 커밋했다. 그 확인이 이 트랜잭션의 스냅샷을 잡아 같은 조회로는 보이지 않는다.
             * 잠금 읽기로 최신 커밋을 읽어 그 판정을 돌려준다(#781).
             */
            return ImpressionResponse.of(impressionLogRepository.findByIdForShare(id).orElseThrow());
        }
        if (log.isValid()) {
            ServeRecord record = found.orElseThrow();
            countAfterCommit(receivedDate, record.campaignId(), !record.campaignId().equals(request.campaignId()));
            /*
             * 분석에 넘길 일을 커밋 전에 기록해 두지 않는다(장애 처리 가이드 "롤백 안 되는 효과"의 기본을 벗어난다).
             * 퍼널은 유실을 허용하고 노출 수는 impression_log 로 다시 센다(아키텍처 설계 2.4).
             * 수신자가 AFTER_COMMIT 으로 받아 롤백된 노출은 넘어가지 않는다.
             */
            eventPublisher.publishEvent(new ImpressionServedEvent(EVENT_ID_PREFIX + serveId, log.getDisplayedAt(),
                    memberId, record.campaignId(), record.storeId(), serveId));
        }
        return ImpressionResponse.of(log);
    }

    private ImpressionLog judge(Long memberId, UUID serveId, LocalDate receivedDate, ImpressionRequest request,
                                Optional<ServeRecord> found) {
        Sighting sighting = new Sighting(
                request.displayedAt().atZoneSameInstant(ClockConfig.ZONE).toLocalDateTime(),
                request.latitude(), request.longitude(), request.viewedMs());
        if (found.isEmpty()) {
            return ImpressionLog.unknown(serveId, memberId, receivedDate, request.campaignId(), sighting);
        }
        ServeRecord record = found.get();
        Placement placement = new Placement(record.campaignId(), record.storeId(), record.slotType(), record.slotPosition());
        if (!record.memberId().equals(memberId)) {
            return ImpressionLog.notOwner(serveId, memberId, receivedDate, placement, sighting);
        }
        boolean soldOut = adCandidateRepository.existsByIdBusinessDateAndIdCampaignIdAndSoldOutAtIsNotNull(
                receivedDate, record.campaignId());
        return ImpressionLog.valid(serveId, memberId, receivedDate, placement, sighting, soldOut);
    }

    /*
     * 피드의 노출 상한이 읽는 카운터는 트랜잭션이 되돌릴 수 없어 커밋된 뒤에 올린다(장애 처리 가이드 "롤백 안 되는 효과").
     * 커밋 전에 올리면 롤백된 노출까지 세어 상한이 이르게 걸린다.
     * 캠페인 불일치 지표도 여기서 센다. 다시 온 요청과 롤백된 요청은 세지 않는다(#783).
     */
    private void countAfterCommit(LocalDate receivedDate, Long campaignId, boolean campaignMismatch) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                impressionCountRepository.increment(receivedDate, campaignId);
                if (campaignMismatch) {
                    meterRegistry.counter(CAMPAIGN_MISMATCH_METRIC).increment();
                }
            }
        });
    }
}
