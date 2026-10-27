package com.lunchcatch.adserving.impression.job;

import com.lunchcatch.adserving.impression.service.ImpressionDispatchService;
import com.lunchcatch.global.logging.QuietSchedulerLog;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 커밋된 유효 노출을 1초마다 정산에 넘긴다(노출 수집 v2, #1010). 배치 서버에서만 돈다(lunchcatch.scheduler.enabled).
 * 앞 실행이 끝난 뒤 주기를 센다(fixedDelay). 정산이 느려도 실행이 겹쳐 쌓이지 않는다.
 * 배치 서버 두 대가 모두 시도하고 Valkey 점유로 한 대만 넘긴다. 1초마다라 정상 실행 로그를 DEBUG 로 내린다.
 */
@Component
@RequiredArgsConstructor
public class ImpressionDispatchJob {

    private final ImpressionDispatchService dispatch;

    @QuietSchedulerLog
    @Scheduled(fixedDelay = 1_000)
    public void dispatchPending() {
        dispatch.dispatchPending();
    }
}
