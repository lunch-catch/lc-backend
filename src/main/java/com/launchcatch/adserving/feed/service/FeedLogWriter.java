package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.config.FeedLogExecutorConfig;
import com.launchcatch.adserving.feed.repository.FeedVisitDailyRepository;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import com.launchcatch.adserving.feed.repository.ServeLogRepository.ServeLogRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/*
 * 피드 응답 뒤에 serve_log 와 그날 첫 요청 위치를 쓴다.
 *
 * 노출 검증은 Valkey 의 serve 기록으로 하므로 serve_log 는 판정과 과금에 쓰이지 않는다. 동기로 쓰면 초당 5,000행의 쓰기가
 * 응답 지연에 들어가고 DB 쓰기 실패가 피드 실패가 된다(feed-api-design.md). 그래서 대기열에 넘기고 기다리지 않는다.
 * 대기열이 차서 버린 건과 쓰기에 실패한 건은 지표로 센다. 세지 않으면 유실률을 잴 수 없다.
 */
@Slf4j
@Service
public class FeedLogWriter {

    static final String DROPPED_METRIC = "adserving_feed_log_dropped_total";
    static final String FAILED_METRIC = "adserving_feed_log_failed_total";

    // 접속 위치는 소수점 3자리(약 100m)로 반올림해 남긴다(api-spec.md 개인정보)
    private static final int LOCATION_SCALE = 3;

    private final Executor executor;
    private final ServeLogRepository serveLogRepository;
    private final FeedVisitDailyRepository feedVisitDailyRepository;
    private final MeterRegistry meterRegistry;

    public FeedLogWriter(@Qualifier(FeedLogExecutorConfig.FEED_LOG_EXECUTOR) Executor executor,
                         ServeLogRepository serveLogRepository,
                         FeedVisitDailyRepository feedVisitDailyRepository,
                         MeterRegistry meterRegistry) {
        this.executor = executor;
        this.serveLogRepository = serveLogRepository;
        this.feedVisitDailyRepository = feedVisitDailyRepository;
        this.meterRegistry = meterRegistry;
    }

    // rows 가 비어 있으면 접속 위치만 쓴다
    public void write(List<ServeLogRow> rows, LocalDate businessDate, Long memberId, double latitude,
                      double longitude, LocalDateTime now) {
        BigDecimal latRounded = BigDecimal.valueOf(latitude).setScale(LOCATION_SCALE, RoundingMode.HALF_UP);
        BigDecimal lngRounded = BigDecimal.valueOf(longitude).setScale(LOCATION_SCALE, RoundingMode.HALF_UP);
        try {
            executor.execute(() -> persist(rows, businessDate, memberId, latRounded, lngRounded, now));
        } catch (RejectedExecutionException e) {
            meterRegistry.counter(DROPPED_METRIC).increment(rows.size());
            log.warn("event=FEED_LOG_DROPPED memberId={} rows={} 대기열이 차서 버린다", memberId, rows.size());
        }
    }

    private void persist(List<ServeLogRow> rows, LocalDate businessDate, Long memberId, BigDecimal latRounded,
                         BigDecimal lngRounded, LocalDateTime now) {
        try {
            if (!rows.isEmpty()) {
                serveLogRepository.saveAll(rows, now);
            }
            feedVisitDailyRepository.saveIfAbsent(businessDate, memberId, latRounded, lngRounded, now);
        } catch (RuntimeException e) {
            meterRegistry.counter(FAILED_METRIC).increment(rows.size());
            log.error("event=FEED_LOG_FAILED memberId={} rows={}", memberId, rows.size(), e);
        }
    }
}
