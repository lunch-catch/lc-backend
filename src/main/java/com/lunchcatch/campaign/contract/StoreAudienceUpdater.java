package com.lunchcatch.campaign.contract;

import java.util.List;

/**
 * 광고 서빙이 00:00 에 집계한 가게별 대상 인원을 덮어쓰는 명령 인터페이스이다(추천 하루 예산의
 * 입력). 한 번 호출이 한 트랜잭션이다. 끊어서 여러 번 부르는 건 광고 서빙의 몫이다.
 *
 * <p>{@code cells} 가 {@link #MAX_CELLS} 행을 넘으면 {@link IllegalArgumentException} 으로 거부한다(#178,
 * {@code qa-data-integrity-guideline.md} 89행). 대상 성별이 {@code ALL} 인 셀도 받는다(#218).
 */
public interface StoreAudienceUpdater {

    /*
     * 한 번에 넘길 수 있는 칸 수의 상한이다. 한 호출이 한 트랜잭션이라 갱신 행 수를 이 값 이하로 묶는다.
     * 광고 서빙은 칸이 이보다 많으면 이 값 이하로 나눠 부른다(#652).
     */
    int MAX_CELLS = 1000;

    void overwrite(List<StoreAudienceCell> cells);
}
