package com.lunchcatch.campaign.service;

/*
 * 가게 요약 갱신이 캠페인 한 건의 데이터를 거부했다는 뜻이다(#989). 예를 들어 할인 대상 메뉴가 그 가게 메뉴가 아닐 때
 * store 계약이 IllegalArgumentException 을 던지고, CampaignStatusService 가 그것을 이 예외로 감싼다.
 *
 * 00:00 전이는 이 예외만 그 캠페인의 데이터 오류로 보고 건너뛴다. 코드 버그로 나는 일반 IllegalArgumentException 까지
 * 건너뛰면 단계가 성공으로 닫혀 정산이 돌고 종료해야 할 캠페인이 ACTIVE 로 남기 때문이다. IllegalArgumentException 의
 * 하위 타입으로 둔 것은 낮 전이(CampaignPauseResumeService)가 이미 그 타입을 잡아 처리하기 때문이다.
 */
final class StoreSummaryRejectedException extends IllegalArgumentException {

    StoreSummaryRejectedException(IllegalArgumentException cause) {
        super(cause.getMessage(), cause);
    }
}
