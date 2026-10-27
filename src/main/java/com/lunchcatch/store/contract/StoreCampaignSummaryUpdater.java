package com.lunchcatch.store.contract;

import java.util.List;

public interface StoreCampaignSummaryUpdater {

    /**
     * 호출자의 쓰기 트랜잭션에서 해당 영업일과 캠페인의 요약을 생성하거나 갱신한다.
     * 캠페인 상태를 변경하는 호출자가 같은 트랜잭션 안에서 호출한다.
     */
    void update(long storeId, long campaignId, StoreCampaignSummaryCommand command);

    /**
     * 호출자의 쓰기 트랜잭션에서 여러 요약을 일괄 생성하거나 갱신한다.
     * 일일 요약을 준비하는 배치가 캠페인마다 update를 반복 호출하는 대신 사용한다.
     * 빈 목록은 저장하지 않으며 어느 항목이든 실패하면 모든 저장을 함께 롤백한다.
     */
    void updateAll(List<StoreCampaignSummaryUpdate> updates);
}
