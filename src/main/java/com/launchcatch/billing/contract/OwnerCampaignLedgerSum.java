package com.launchcatch.billing.contract;

/*
 * 한 영업일의 점주와 캠페인별 원장 유형별 합계다. 모든 값은 저장 부호 그대로의 합이고 행이 없으면 0 이다.
 * 그래서 RESERVE, DEDUCT, REFUND 와 음수 ADJUST 는 0 이하이고 CHARGE, RELEASE 와 양수 ADJUST 는 0 이상이다.
 *
 * campaignId 는 캠페인에 묶이지 않는 CHARGE, REFUND 와 캠페인 없는 ADJUST 에서 null 이다.
 * 영업일 귀속은 RESERVE, RELEASE 가 원장의 영업일, DEDUCT 가 차감 판정의 영업일, 나머지가 기록 시각의 KST 날짜다.
 * ADJUST 가 정정 대상 영업일이 아니라 기록 시각으로 귀속되는 것은 임시다(이슈 352).
 */
public record OwnerCampaignLedgerSum(
        Long ownerId,
        Long campaignId,
        long chargeAmount,
        long reserveAmount,
        long deductAmount,
        long releaseAmount,
        long refundAmount,
        long positiveAdjustAmount,
        long negativeAdjustAmount
) {
}
