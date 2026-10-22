package com.launchcatch.billing.job;

import com.launchcatch.billing.service.FundingJudgementService;
import com.launchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음의 잔액 부족 판정 단계다(V52 102행 (6)). 순서 값은 102행 단계 번호 × 10 이다.
 * 예약과 나눈 이유는 실패했을 때 다시 도는 범위를 판정 통보로 좁히기 위해서다.
 */
@Component
@Order(60)
@RequiredArgsConstructor
public class FundingJudgementJob implements DailyJob {

    static final String JOB_NAME = "BILLING_FUNDING_JUDGE";

    private final FundingJudgementService judgement;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        judgement.judge(businessDate);
    }
}
