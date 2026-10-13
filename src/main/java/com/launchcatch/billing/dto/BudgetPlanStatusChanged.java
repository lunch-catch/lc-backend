package com.launchcatch.billing.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * 정산 안에서만 쓰는 통보다. 그날 계획 행의 중단 표시가 실제로 바뀌었다는 뜻이다.
 * 커밋 뒤에 Valkey 해시를 맞추려고 둔다. 다른 도메인에 공개하지 않으므로 이름에 Event 를 붙이지 않는다.
 */
public record BudgetPlanStatusChanged(
        Long campaignId,
        LocalDate businessDate,
        boolean paused,
        LocalDateTime changedAt
) {
}
