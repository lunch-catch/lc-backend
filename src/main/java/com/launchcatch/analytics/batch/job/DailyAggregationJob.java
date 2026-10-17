package com.launchcatch.analytics.batch.job;

import com.launchcatch.analytics.batch.AggregationOutcome;
import com.launchcatch.analytics.batch.service.DailyAggregationRunner;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 매일 01:00 에 전일, 전전일, 미처리 날짜를 집계한다.
 *
 * 00:00 묶음(DailyJob)에 태우지 않는다. 묶음은 앞 단계가 실패하면 뒤 단계가 멈추는데, 대시보드 집계가
 * 정산 예약 실패에 묶일 이유가 없다.
 *
 * 배치 서버 두 대가 같은 시각에 시도하고, 날짜마다 batch_execution_log 점유로 한 서버만 돈다.
 * @EnableScheduling 은 launchcatch.scheduler.enabled=true 인 배치 서버에서만 뜬다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyAggregationJob {

    private final DailyAggregationRunner runner;

    /*
     * 시간대를 값으로 적는다. 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 오전 10시에 돈다.
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Seoul")
    public void run() {
        Map<LocalDate, AggregationOutcome> outcomes = runner.runAll();
        log.info("event=DAILY_AGGREGATION_DONE outcomes={}", outcomes);
    }
}
