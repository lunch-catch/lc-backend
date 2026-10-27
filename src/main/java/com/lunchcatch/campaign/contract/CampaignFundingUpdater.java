package com.lunchcatch.campaign.contract;

/*
 * 정산이 캠페인 상태를 바꾸는 명령 인터페이스.
 * 잔액 부족으로 판정하면 PAUSED(NO_POINTS), 충전 후 재개로 판정하면 ACTIVE 로 상태만 바꾼다.
 * 구현은 campaign 의 service 에 둔다.
 *
 * PAUSED(ADMIN), PAUSED(OWNER), ENDED 에 RESUME 이 오면 조용히 무시한다(#198 질문 4 답변).
 * 예외를 던지면 정산의 00:00 배치나 재시도가 멈춰야 할 이유가 없는데도 멈춘다. 같은 판정을
 * 두 번 호출해도 결과가 같다(멱등).
 */
public interface CampaignFundingUpdater {

    void applyFundingJudgement(FundingJudgement judgement);
}
