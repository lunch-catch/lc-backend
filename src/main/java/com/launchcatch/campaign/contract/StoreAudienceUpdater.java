package com.launchcatch.campaign.contract;

import java.util.List;

/**
 * 광고 서빙이 00:00 에 집계한 가게별 대상 인원을 덮어쓰는 명령 인터페이스이다(추천 하루 예산의
 * 입력). 한 번 호출이 한 트랜잭션이다. 끊어서 여러 번 부르는 건 광고 서빙의 몫이다.
 *
 * <p>{@code cells} 가 1000 행을 넘으면 {@link IllegalArgumentException} 으로 거부한다(#178,
 * {@code qa-data-integrity-guideline.md} 89행). 대상 성별이 {@code ALL} 인 셀도 받는다(#218).
 */
public interface StoreAudienceUpdater {

    void overwrite(List<StoreAudienceCell> cells);
}
