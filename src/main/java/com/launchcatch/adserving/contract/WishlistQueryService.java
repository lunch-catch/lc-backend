package com.launchcatch.adserving.contract;

import java.util.List;
import java.util.Set;

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

    /** findWishedMemberIds 에 한 번에 넘길 수 있는 회원 수다. 쿠폰 묶음 발급의 최대 인원이다(#578). */
    int MAX_WISHED_MEMBER_IDS = 500;

    /**
     * 입력한 회원 가운데 해당 캠페인을 현재 유효하게 찜한 회원 ID 를 돌려준다. 결과는 수정할 수 없고 순서는 정하지 않는다.
     * 회원 집합이 비면 조회하지 않고 빈 집합이다. 잠그지 않고 읽어 호출자의 트랜잭션 스냅샷을 본다.
     * 조회 장애는 빈 집합으로 바꾸지 않고 올린다.
     *
     * @throws IllegalArgumentException campaignId 나 memberIds 가 null 이거나 회원이 MAX_WISHED_MEMBER_IDS 를 넘는 경우
     */
    Set<Long> findWishedMemberIds(Long campaignId, Set<Long> memberIds);
}
