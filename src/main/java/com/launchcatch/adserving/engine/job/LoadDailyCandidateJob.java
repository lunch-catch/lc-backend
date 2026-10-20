package com.launchcatch.adserving.engine.job;

import com.launchcatch.adserving.engine.service.CandidateLoadService;
import com.launchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 그날의 피드 후보를 적재하는 00:00 단계다(FR-099 (8)). 정산의 예약(50)과 시간대별 목표(70) 뒤, 끝난 찜 정리(80) 앞이다(#582).
 * 예약보다 먼저 돌면 계획이 없는 캠페인을 내보내 그 노출이 모두 NOT_BILLED_NO_PLAN 이 된다.
 * 적재와 재조회를 따로 불러 두 트랜잭션으로 나눈다. 재조회가 적재 중에 커밋된 상태 전이를 보게 하려는 것이다.
 */
@Component
@Order(75)
@RequiredArgsConstructor
public class LoadDailyCandidateJob implements DailyJob {

    private final CandidateLoadService candidateLoadService;

    @Override
    public String jobName() {
        return "ADSERVING_CANDIDATE_LOAD";
    }

    @Override
    public void run(LocalDate businessDate) {
        Map<Long, LocalDateTime> loaded = candidateLoadService.load(businessDate);
        candidateLoadService.reconcile(businessDate, loaded);
    }
}
