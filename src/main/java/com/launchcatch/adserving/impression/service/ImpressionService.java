package com.launchcatch.adserving.impression.service;

import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.repository.ImpressionCountRepository;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.impression.dto.ImpressionRequest;
import com.launchcatch.adserving.impression.dto.ImpressionResponse;
import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLog.Sighting;
import com.launchcatch.adserving.impression.entity.ImpressionLogId;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository;
import com.launchcatch.billing.contract.PointDeductor;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/*
 * 카드가 보인 순간 1건을 받아 유효와 무효를 가르고 저장한다(FR-066, FR-097).
 * 유효 노출의 저장, 정산의 판정, 원장은 한 트랜잭션이다. 함께 커밋되고 함께 롤백된다(FR-100).
 */
@Service
@RequiredArgsConstructor
public class ImpressionService {

    // 요청의 캠페인이 serve 기록과 달랐던 횟수다. 판정은 serve 기록으로 한다(#323).
    static final String CAMPAIGN_MISMATCH_METRIC = "adserving_impression_campaign_mismatch_total";

    private final ServeRecordRepository serveRecordRepository;
    private final ImpressionLogRepository impressionLogRepository;
    private final AdCandidateRepository adCandidateRepository;
    private final ImpressionCountRepository impressionCountRepository;
    private final PointDeductor pointDeductor;
    private final MeterRegistry meterRegistry;
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

        Optional<ServeRecord> found = serveRecordRepository.findById(serveId);
        ImpressionLog log = judge(memberId, serveId, request, found);
        if (!impressionLogRepository.insertIgnore(log)) {
            return ImpressionResponse.of(impressionLogRepository.findById(id).orElseThrow());
        }
        if (log.isValid()) {
            ServeRecord record = found.orElseThrow();
            LocalDate servedDate = servedDate(record);
            if (!log.isSoldOut()) {
                pointDeductor.deduct(serveId, record.campaignId(), servedDate);
            }
            countAfterCommit(servedDate, record.campaignId());
        }
        return ImpressionResponse.of(log);
    }

    private ImpressionLog judge(Long memberId, UUID serveId, ImpressionRequest request, Optional<ServeRecord> found) {
        LocalDate receivedDate = LocalDate.now(clock);
        Sighting sighting = new Sighting(
                request.displayedAt().atZoneSameInstant(ClockConfig.ZONE).toLocalDateTime(),
                request.latitude(), request.longitude(), request.viewedMs());
        if (found.isEmpty()) {
            return ImpressionLog.unknown(serveId, memberId, receivedDate, request.campaignId(), sighting);
        }
        ServeRecord record = found.get();
        if (!record.memberId().equals(memberId)) {
            return ImpressionLog.notOwner(memberId, receivedDate, record, sighting);
        }
        if (!record.campaignId().equals(request.campaignId())) {
            meterRegistry.counter(CAMPAIGN_MISMATCH_METRIC).increment();
        }
        boolean soldOut = adCandidateRepository.existsByIdBusinessDateAndIdCampaignIdAndSoldOutAtIsNotNull(
                servedDate(record), record.campaignId());
        return ImpressionLog.valid(memberId, receivedDate, record, sighting, soldOut);
    }

    // 차감과 카운터는 노출을 받은 날이 아니라 카드를 내려준 영업일로 센다(PointDeductor 계약).
    private static LocalDate servedDate(ServeRecord record) {
        return record.servedAt().atZone(ClockConfig.ZONE).toLocalDate();
    }

    /*
     * 피드의 노출 상한이 읽는 카운터는 트랜잭션이 되돌릴 수 없어 커밋된 뒤에 올린다(장애 처리 가이드 "롤백 안 되는 효과").
     * 커밋 전에 올리면 롤백된 노출까지 세어 상한이 이르게 걸린다.
     */
    private void countAfterCommit(LocalDate servedDate, Long campaignId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                impressionCountRepository.increment(servedDate, campaignId);
            }
        });
    }
}
