package com.launchcatch.adserving.contract;

import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * 쿠폰이 선착순 소진을 그날 후보에 알리는 명령이다.
 * 쿠폰 발급 트랜잭션이 커밋된 뒤 트랜잭션 밖에서 부르고, 광고 서빙은 자기 트랜잭션에서 반영한다. 실패해도 발급은 그대로다.
 * 다시 불러도 결과가 같다. 이미 소진된 후보는 처음 소진 시각을 남기고, 그 영업일의 후보가 없으면 예외 없이 반환한다(#163).
 */
public interface CandidateStateUpdater {

    /** 해당 영업일의 캠페인 광고 후보에 전달받은 시각으로 쿠폰 소진을 반영한다. */
    void markSoldOut(
            Long campaignId,
            LocalDate businessDate,
            LocalDateTime soldOutAt
    );
}
