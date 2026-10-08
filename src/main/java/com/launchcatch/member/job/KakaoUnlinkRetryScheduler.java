package com.launchcatch.member.job;

import com.launchcatch.member.service.KakaoUnlinkRetryService;
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
 * batch_execution_log 로 점유하고 이긴 서버만 돈다 — 배치 운영 문서 2장의 "중복 실행" 규칙이
 * 이 프로젝트의 표준 답이고, 행마다 선점 토큰을 두는 방법을 따로 만들지 않는다. 점유를 쓰면
 * 실행 이력, 생존 신호(renewOwned), 이어받기 감지가 그 표에 딸려 온다.
 *
 * 00:00 묶음(DailyJob)에는 태우지 않는다. 그 묶음은 앞 단계가 실패하면 뒤가 멈추는 순서
 * 보장용이고 03시 작업은 그 순서에 속하지 않는다.
 *
 * @EnableScheduling 은 SchedulingConfig 에만 있고 launchcatch.scheduler.enabled=true 일 때만
 * 뜬다. 그 값은 application-batch.yml 에만 있어 API 서버에서는 이 메서드가 돌지 않는다.
 *
 * 점유한 행은 반드시 닫는다. RUNNING 으로 남기면 2분 뒤 다른 서버의 takeOverStale 이
 * 가져가 "다시 돌릴 경로가 없다" 로 실패 처리하고 관리자에게 알린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUnlinkRetryScheduler {

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
            log.info("카카오 unlink 재시도는 다른 배치 서버가 점유했다. businessDate={}", businessDate);
            return;
        }
        try {
            kakaoUnlinkRetryService.retryPending();
        } catch (RuntimeException e) {
            executions.fail(JOB_NAME, businessDate, e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        executions.succeed(JOB_NAME, businessDate);
    }
}
