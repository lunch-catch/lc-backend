package com.launchcatch.adserving.contract;

// 한 영업일에 회원 하나가 캠페인 하나로 보낸 무효 노출의 사유별 건수다. memberId 는 노출을 보낸 회원이다
public record MemberInvalidImpressionCount(
        Long campaignId,
        Long memberId,
        long expiredOrUnknownCount,
        long notOwnerCount
) {
}
