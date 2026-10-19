package com.launchcatch.adserving.contract;

import java.util.List;

/*
 * 쿠폰이 발급 자격과 이벤트 상태 조회에 쓰는 찜 조회다.
 * 현재 유효한 찜은 wishlist 행이 있고 캠페인이 끝나지 않은 것이다(#163, #548).
 * 찜을 지우면 행이 지워진다. 캠페인이 끝나면 행은 3일 더 남지만 종료로 표시돼 유효하지 않다.
 */
public interface WishlistQueryService {

    /** 회원이 해당 캠페인을 현재 유효하게 찜했으면 true를 반환한다. */
    boolean isWished(Long memberId, Long campaignId);

    /** 회원이 현재 유효하게 찜한 캠페인 ID 목록을 조회하며, 찜이 없으면 빈 리스트를 반환한다. */
    List<Long> findWishedCampaignIds(Long memberId);
}
