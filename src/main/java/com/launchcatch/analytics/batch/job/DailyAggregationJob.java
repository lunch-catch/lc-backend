package com.launchcatch.analytics.batch.job;

import com.launchcatch.analytics.DailyAggregation;
import com.launchcatch.analytics.batch.AggregationOutcome;
import com.launchcatch.analytics.batch.service.DailyAggregationRunner;
import com.launchcatch.ops.scheduler.StandaloneJob;
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
 *
 * 멈춘 날짜를 다른 서버가 이어받거나 관리자가 실패한 날짜를 다시 돌리면 StandaloneJob 으로 이 작업을 찾는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyAggregationJob implements StandaloneJob {

    private final DailyAggregationRunner runner;

    /*
     * 시간대를 값으로 적는다. 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 오전 10시에 돈다.
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Seoul")
    public void run() {
        Map<LocalDate, AggregationOutcome> outcomes = runner.runAll();
        log.info("event=DAILY_AGGREGATION_DONE outcomes={}", outcomes);
    }

    @Override
    public String jobName() {
        return DailyAggregation.JOB_NAME;
    }

    /*
     * 이어받은 서버와 관리자 수동 재실행이 부른다. 그 날짜는 이미 점유돼 있어 다시 잡지 않고 넘겨받은 세대로
     * 그 날짜만 집계한다.
     *
     * 집계가 실행 행을 건수와 함께 먼저 닫는다. BatchJobRunner 가 이어서 부르는 건수 없는 succeed 는 행이 이미
     * RUNNING 이 아니라서 무시된다. EventLogRetentionJob 과 같은 임시 처리다(#559, #560).
     *
     * 실행권을 잃어도 예외를 던지지 않는다. 던지면 BatchJobRunner 가 소유자 조건만으로 fail 을 불러, 같은
     * 서버가 잡은 새 세대의 행을 실패로 닫는다. 던지지 않아도 이어지는 succeed 가 그 행을 닫는 틈은 남는다.
     * 그 틈은 ops 가 세대로 닫게 해야 막히고 #591 이 맡는다.
     */
    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        AggregationOutcome outcome = runner.aggregate(businessDate, attemptNo);
        log.info("event=DAILY_AGGREGATION_RESUMED businessDate={} attemptNo={} outcome={}",
                businessDate, attemptNo, outcome);
    }
}
