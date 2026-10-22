package com.launchcatch.member.job;

import com.launchcatch.global.logging.FailureLogDetails;
import com.launchcatch.member.service.KakaoUnlinkRetryService;
import com.launchcatch.ops.scheduler.StandaloneJob;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 매일 03시에 해제되지 않은 카카오 unlink 를 재시도한다.
 *
 * 배치 서버 두 대가 모두 이 메서드를 같은 시각에 시도한다(액티브-액티브). 그래서 작업을
 * batch_execution_log 로 점유하고 이긴 서버만 돈다. 배치 운영 문서 2장의 "중복 실행" 규칙이
 * 이 프로젝트의 표준 답이고, 행마다 선점 토큰을 두는 방법을 따로 만들지 않는다. 점유를 쓰면
 * 실행 이력, 생존 신호(renewOwned), 이어받기 감지가 그 표에 딸려 온다.
 *
 * 00:00 묶음(DailyJob)에는 태우지 않는다. 그 묶음은 앞 단계가 실패하면 뒤가 멈추는 순서
 * 보장용이고 03시 작업은 그 순서에 속하지 않는다.
 *
 * @EnableScheduling 은 SchedulingConfig 에만 있고 launchcatch.scheduler.enabled=true 일 때만
 * 뜬다. 그 값은 application-batch.yml 에만 있어 API 서버에서는 이 메서드가 돌지 않는다.
 *
 * 점유한 행은 반드시 닫는다. RUNNING 으로 남기면 2분 뒤 다른 서버가 이어받는다. 그 길이
 * StandaloneJob 이다(#621). 관리자 수동 재실행도 같은 길로 다시 돈다.
 *
 * 같은 영업일로 다시 돌아도 안전하다. 해제에 성공한 행은 지워지고 한도에 닿거나 거부된 행은
 * resolved 로 닫혀 후보에서 빠진다. 멈춘 실행이 선점한 채 남긴 행은 processing 이라 다시 돈
 * 실행도 집지 않는다. 그래서 같은 행을 두 번 부르지 않고, 그 행은 장애 처리 설계의 실행 중단
 * 복구를 따른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUnlinkRetryScheduler implements StandaloneJob {

    /** 점유 키다. 도메인을 접두어로 붙여 다른 도메인의 작업명과 겹치지 않게 한다. */
    static final String JOB_NAME = "MEMBER_KAKAO_UNLINK_RETRY";

    private final KakaoUnlinkRetryService kakaoUnlinkRetryService;
    private final BatchExecutionService executions;

    /*
     * 영업일 경계 작업과 같이 시간대를 값으로 적는다.
     * 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 정오에 돈다.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void retryPendingUnlinks() {
        LocalDate businessDate = executions.businessDate();
        if (!executions.claim(JOB_NAME, businessDate)) {
            log.info("event=KAKAO_UNLINK_RETRY_SKIPPED reason=ALREADY_CLAIMED businessDate={}", businessDate);
            return;
        }
        try {
            kakaoUnlinkRetryService.retryPending();
        } catch (RuntimeException e) {
            try {
                executions.fail(JOB_NAME, businessDate, FailureLogDetails.from(e).toString());
            } catch (RuntimeException recordFailure) {
                e.addSuppressed(recordFailure);
            }
            // 공통 스케줄러 관측이 실패를 인식하고 마지막 성공 시각을 갱신하지 않게 한다.
            throw e;
        }
        executions.succeed(JOB_NAME, businessDate);
    }

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    // 이어받은 서버와 관리자 수동 재실행이 부른다. 행은 BatchJobRunner 가 닫으므로 여기서 닫지 않는다.
    @Override
    public void run(LocalDate businessDate, int attemptNo) {
        kakaoUnlinkRetryService.retryPending();
    }
}
