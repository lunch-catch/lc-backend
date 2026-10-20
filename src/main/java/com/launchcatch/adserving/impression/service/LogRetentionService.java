package com.launchcatch.adserving.impression.service;

import com.launchcatch.adserving.feed.repository.FeedVisitRepository;
import com.launchcatch.adserving.feed.repository.ServeLogRepository;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository;
import java.time.LocalDate;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 광고 서빙 테이블의 보관 기간이 지난 영업일의 행을 지운다(#610). 경계는 모두 그날을 넣어 센다.
 * 접속 위치는 8일(LR-001)이라 D-7 앞을 지운다. 대상 인원 집계(#592)가 읽는 D-7 ~ D-1 이 남는다.
 * 스와이프는 31일이라 D-30 앞을 지운다. 업종 선호도는 D-29 ~ D 를 읽는다.
 * 서빙과 노출은 13개월이라 D 의 13개월 전 같은 날 앞을 지운다. 분석의 재집계 보존 범위와 같은 경계다.
 * 한 문장에 1,000행씩 따로 커밋해 락을 짧게 잡는다. 실패하면 남은 테이블을 건너뛰고 예외를 올린다. 다시 돌면 남은 행만 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogRetentionService {

    static final int BATCH_SIZE = 1000;

    private static final int VISIT_DAYS = 8;
    private static final int SWIPE_DAYS = 31;
    private static final int LOG_MONTHS = 13;

    private final FeedVisitRepository feedVisitRepository;
    private final SwipeLogRepository swipeLogRepository;
    private final ServeLogRepository serveLogRepository;
    private final ImpressionLogRepository impressionLogRepository;

    // 지운 행 수의 합을 돌려준다
    public int purge(LocalDate businessDate) {
        LocalDate logBefore = businessDate.minusMonths(LOG_MONTHS);
        int visits = deleteAll(feedVisitRepository::deleteBusinessDateBefore, businessDate.minusDays(VISIT_DAYS - 1));
        int swipes = deleteAll(swipeLogRepository::deleteBusinessDateBefore, businessDate.minusDays(SWIPE_DAYS - 1));
        int serves = deleteAll(serveLogRepository::deleteBusinessDateBefore, logBefore);
        int impressions = deleteAll(impressionLogRepository::deleteBusinessDateBefore, logBefore);
        log.info("event=ADSERVING_LOG_PURGED businessDate={} visits={} swipes={} serves={} impressions={}",
                businessDate, visits, swipes, serves, impressions);
        return visits + swipes + serves + impressions;
    }

    private static int deleteAll(BiFunction<LocalDate, Integer, Integer> delete, LocalDate before) {
        int total = 0;
        int deleted;
        do {
            deleted = delete.apply(before, BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
