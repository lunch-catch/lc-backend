package com.launchcatch.adserving.engine.job;

import com.launchcatch.adserving.engine.service.CandidateBudgetService;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 10분마다 그날 후보의 예약액과 단가를 정산에서 다시 읽는다(#837). 충전 재예약으로 장중에 늘어난 예약액을 반영한다.
 * batch_execution_log 로 점유하지 않는다. 같은 값을 덮어쓰기만 해서 배치 서버 두 대가 함께 돌아도 결과가 같다.
 * 그 표는 영업일마다 한 행이라 10분마다의 실행을 남길 수도 없다.
 * 시간대를 값으로 적는다. 서버 기본 시간대로 두면 컨테이너가 UTC 로 뜨는 순간 영업일이 어긋난다.
 */
@Component
@RequiredArgsConstructor
public class CandidateBudgetRefreshJob {

    private final CandidateBudgetService candidateBudgetService;
    private final Clock clock;

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void refresh() {
        candidateBudgetService.refresh(LocalDate.now(clock));
    }
}
