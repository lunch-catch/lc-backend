package com.launchcatch.adserving.impression.service;

import com.launchcatch.adserving.engine.service.CandidateCache;
import com.launchcatch.adserving.feed.repository.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.impression.dto.ImpressionRequest;
import com.launchcatch.adserving.impression.dto.ImpressionResponse;
import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLog.Placement;
import com.launchcatch.adserving.impression.entity.ImpressionLog.Sighting;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 카드가 보인 순간 1건을 받아 유효와 무효를 가르고 응답한다(FR-066, FR-097).
 * 저장은 응답 뒤에 ImpressionWriteBuffer 가 묶어서 한다(노출 수집 v3). 클라이언트는 응답에 기대지 않는다.
 * 정산을 부르지 않는다. 커밋된 유효 노출은 ImpressionDispatchService 가 모아 정산에 넘긴다(노출 수집 v2, #1010).
 */
@Service
@RequiredArgsConstructor
public class ImpressionService {

    private final ServeRecordRepository serveRecordRepository;
    private final CandidateCache candidateCache;
    private final ImpressionWriteBuffer writeBuffer;
    private final Clock clock;

    /*
     * 같은 사용자가 같은 카드를 다시 보내도 저장된 노출을 확인하지 않고 다시 판정해 돌려준다(#1044).
     * 클라이언트는 응답을 쓰지 않는다. 저장은 INSERT IGNORE 라 처음 것만 남고 카운터와 이벤트도 처음 저장에서만 나간다.
     * DB 를 쓰지 않는다. 그날 후보와 소진은 서버 메모리에서, serve 기록은 Valkey 에서 읽는다.
     */
    public ImpressionResponse record(Long memberId, ImpressionRequest request) {
        UUID serveId = UUID.fromString(request.serveId());

        /*
         * 판정, 소진 확인, 카운터가 같은 날을 보게 받은 시각을 한 번만 읽는다. 릴레이도 이 날로 정산에 넘긴다(PointDeductor 계약).
         * 저장 시각(created_at)도 이 값이다. 따로 읽으면 자정 직전 노출이 전날의 0시 구간으로 집계된다(#783).
         * created_at 의 정밀도(DATETIME(6))로 자른다. 묶음 저장이 이 값으로 새로 넣은 행을 가린다.
         */
        LocalDateTime receivedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        Optional<ServeRecord> found = serveRecordRepository.findById(serveId);
        ImpressionLog log = judge(memberId, serveId, receivedAt.toLocalDate(), request, found);
        boolean campaignMismatch = log.isValid() && !found.orElseThrow().campaignId().equals(request.campaignId());
        writeBuffer.offer(new QueuedImpression(log, receivedAt, campaignMismatch));
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
        boolean soldOut = candidateCache.isSoldOut(receivedDate, record.campaignId());
        return ImpressionLog.valid(serveId, memberId, receivedDate, placement, sighting, soldOut);
    }
}
