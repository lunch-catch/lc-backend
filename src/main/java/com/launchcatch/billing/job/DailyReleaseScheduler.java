package com.launchcatch.billing.job;

import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.service.DailyReleaseService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/*
 * 13:30 해제·대사다(구현 순서 11번). 00:00 묶음(DailyJob)이 아니라 정산의 스케줄 작업이다.
 *
 * 실행 시각은 상수로 박지 않는다. @Scheduled(cron) 은 상수 문자열만 받아 설정값을 쓸 수 없어서,
 * SchedulingConfigurer 로 BillingScheduleProperties.releaseTime() 이 계산한 시각을 크론 문자열로
 * 바꿔 등록한다. 값이 그 메서드 한 곳에서만 나와, 서빙 종료 시각이나 노출 인정 시간을 바꾸면 실행
 * 시각도 함께 바뀐다. 등록을 별도 config 클래스로 떼지 않는 이유는 Entry 계층(job, listener)을
 * 다른 계층이 참조하지 못하게 하는 계층_의존_규칙 때문이다. config 가 이 클래스를 주입받으면 그 규칙이
 * 떨어진다.
 *
 * configureTasks 는 @EnableScheduling 이 없는 서버(API 서버)에서는 불리지 않는다. 그 메서드를 부르는
 * ScheduledAnnotationBeanPostProcessor 자체가 그 애너테이션이 있을 때만 등록되기 때문이다.
 *
 * 배치 서버 두 대가 같은 시각에 시도하므로 batch_execution_log 점유로 겹치지 않게 한다(배치 운영 문서
 * 2장). KakaoUnlinkRetryScheduler 와 같은 패턴이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReleaseScheduler implements SchedulingConfigurer {

    /** 점유 키다. 영업일은 붙이지 않는다. */
    static final String JOB_NAME = "BILLING_DAILY_RELEASE";

    private final DailyReleaseService releaseService;
    private final BatchExecutionService executions;
    private final BillingScheduleProperties schedule;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        LocalTime releaseTime = schedule.releaseTime();
        String cron = "0 %d %d * * *".formatted(releaseTime.getMinute(), releaseTime.getHour());
        registrar.addCronTask(new CronTask(this::release, new CronTrigger(cron, ClockConfig.ZONE)));
    }

    public void release() {
        LocalDate businessDate = executions.businessDate();
        if (!executions.claim(JOB_NAME, businessDate)) {
            log.info("event=BILLING_DAILY_RELEASE_SKIPPED reason=ALREADY_CLAIMED businessDate={}", businessDate);
            return;
        }
        try {
            releaseService.reconcileAndRelease(businessDate, JOB_NAME);
        } catch (RuntimeException e) {
            try {
                executions.fail(JOB_NAME, businessDate, FailureLogDetails.from(e).toString());
            } catch (RuntimeException recordFailure) {
                e.addSuppressed(recordFailure);
            }
            throw e;
        }
        executions.succeed(JOB_NAME, businessDate);
    }
}
