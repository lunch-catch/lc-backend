package com.launchcatch.campaign.contract;

/*
 * 정산이 캠페인 상태를 바꾸는 명령 인터페이스.
 * 잔액 부족으로 판정하면 PAUSED(NO_POINTS), 충전 후 재개로 판정하면 ACTIVE 로 상태만 바꾼다.
 * 구현은 campaign 의 service 에 둔다.
 */
public interface CampaignFundingUpdater {
}