package com.lunchcatch.adserving.contract;

import java.util.List;
import java.util.Map;

/*
 * 한 영업일에 캠페인 하나가 받은 노출과 찜 건수다. 행이 없으면 0 이다.
 * validCount 는 쿠폰 소진 뒤 노출(soldOutCount, NOT_BILLED_SOLD_OUT)을 포함한다. 그래서 validCount 는 정산의 BILLED, NOT_BILLED_BUDGET, NOT_BILLED_PAUSED, NOT_BILLED_NO_PLAN 합에 soldOutCount 를 더한 값과 같다.
 * hourlyCounts 는 노출을 받은 시각의 시(0~23)를 인덱스로 하는 유효 노출 건수이고 항상 24칸이다. 24칸의 합과 slotCounts 값의 합은 각각 validCount 와 같다.
 * slotCounts 는 피드 엔진이 붙인 슬롯 이름표별 유효 노출 건수다. 이름표는 정해진 목록이 아니라 엔진이 정하고 0건인 이름표는 없다(#978).
 * EXPIRED_OR_UNKNOWN 은 serve 기록이 없는 노출이라 campaignId 가 요청이 보낸 값이다. 그 사유만 있는 행은 없는 캠페인 ID 일 수 있다.
 * wishCount 는 그날 피드 카드에서 찜한 건수다. 가게 상세에서 한 찜은 들어가지 않고 31일이 지난 날은 0 이다(스와이프 기록의 보관 기간).
 */
public record CampaignImpressionStats(
        Long campaignId,
        long validCount,
        long soldOutCount,
        long expiredOrUnknownCount,
        long notOwnerCount,
        List<Long> hourlyCounts,
        Map<String, Long> slotCounts,
        long wishCount
) {

    public CampaignImpressionStats {
        hourlyCounts = List.copyOf(hourlyCounts);
        slotCounts = Map.copyOf(slotCounts);
    }
}
