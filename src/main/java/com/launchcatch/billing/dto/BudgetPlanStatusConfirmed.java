package com.launchcatch.billing.dto;

import java.time.LocalDate;

/*
 * 정산 안에서만 쓰는 통보다. 00:00 예약의 상태 확인이 그날 계획의 중단 표시를 바꿨다는 뜻이다.
 * 커밋 뒤에 Valkey 해시를 지우려고 둔다. 확인은 계획에 저장된 것과 같은 상태 변경 시각으로도 반영하는데, 해시는
 * 더 늦은 시각만 받아서(billing_day_status.lua) 맞출 수 없다. 지우면 다음 차감이나 진행률 조회가 DB 정본으로 다시 만든다.
 */
public record BudgetPlanStatusConfirmed(
        Long campaignId,
        LocalDate businessDate
) {
}
