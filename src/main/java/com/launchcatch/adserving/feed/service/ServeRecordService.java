package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.feed.ServeLogQueue;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.repository.FeedVisitDailyRepository;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import com.launchcatch.adserving.feed.repository.ServeRecordCacheRepository;
import com.launchcatch.adserving.feed.repository.ServedHistoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/*
 * 피드로 내려준 카드를 기록한다.
 *
 * serve 기록과 내려준 이력은 캐시에 바로 쓴다. 노출 검증과 다음 피드의 하루 2회 판정이 곧바로 읽는다.
 * 캐시가 멈추면 통과시키고 센다(장애 처리 가이드의 캐시 기본 처리).
 * serve_log 와 feed_visit_daily 는 응답 뒤 대기열에서 쓴다. 판정과 과금에 쓰이지 않아 피드 응답을 기다리게 하지 않는다(feed-api-design.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServeRecordService {

    static final String DROPPED_METRIC = "adserving.serve.log.dropped";

    /*
     * 서빙 로그 유실률의 분모다.
     * serve_log 건수와 맞대어 잰다(feed-api-design.md 의 유실 목표)
     */
    static final String SERVED_CARDS_METRIC = "adserving.feed.cards.served";

    private static final String SERVE_LOG = "serve_log";
    private static final String FEED_VISIT_DAILY = "feed_visit_daily";

    private final ServeRecordCacheRepository serveRecordCacheRepository;
    private final ServedHistoryRepository servedHistoryRepository;
    private final ServeLogRepository serveLogRepository;
    private final FeedVisitDailyRepository feedVisitDailyRepository;
    private final ServeLogQueue serveLogQueue;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    /*
     * 피드 한 번의 카드를 받는다.
     * 모두 같은 사용자와 같은 영업일이다
     */
    public void record(List<ServeRecord> records) {
        ServeRecord first = records.getFirst();
        meterRegistry.counter(SERVED_CARDS_METRIC).increment(records.size());
        try {
            serveRecordCacheRepository.saveAll(records);
        } catch (DataAccessException e) {
            bypassed("serve_record", first.memberId(), e);
        }
        try {
            servedHistoryRepository.record(first.memberId(), first.businessDate(),
                    records.stream().map(ServeRecord::campaignId).toList());
        } catch (DataAccessException e) {
            bypassed("served_history", first.memberId(), e);
        }
        enqueue(SERVE_LOG, records.size(),
                () -> serveLogRepository.saveAll(records, LocalDateTime.now(clock)));
    }

    /*
     * 그날 첫 피드 요청 위치를 남긴다.
     * 둘째 요청부터는 저장소가 버린다
     */
    public void recordVisit(Long memberId, LocalDate businessDate, double latitude, double longitude) {
        enqueue(FEED_VISIT_DAILY, 1,
                () -> feedVisitDailyRepository.saveFirst(businessDate, memberId, latitude, longitude, LocalDateTime.now(clock)));
    }

    /*
     * 버린 행은 지표로 센다. 세지 않으면 서빙 로그 유실률(비기능 18행)을 잴 수 없다.
     * 쓰기 실패는 재시도하지 않는다. 진단에서 "기록 없음" 이 될 뿐이고 판정과 과금은 바뀌지 않는다(feed-api-design.md 의 유실 목표).
     */
    private void enqueue(String table, int rows, Runnable write) {
        boolean queued = serveLogQueue.offer(() -> {
            try {
                write.run();
            } catch (DataAccessException e) {
                dropped(table, "write_failed", rows);
                log.warn("event=SERVE_LOG_WRITE_FAILED table={} rows={}", table, rows, e);
            }
        });
        if (!queued) {
            dropped(table, "queue_full", rows);
        }
    }

    private void dropped(String table, String reason, int rows) {
        meterRegistry.counter(DROPPED_METRIC, "table", table, "reason", reason).increment(rows);
    }

    private void bypassed(String target, Long memberId, DataAccessException e) {
        meterRegistry.counter(FeedService.CACHE_BYPASS_METRIC, "target", target).increment();
        log.warn("event=FEED_CACHE_BYPASSED target={} memberId={} 통과시킨다", target, memberId, e);
    }
}
