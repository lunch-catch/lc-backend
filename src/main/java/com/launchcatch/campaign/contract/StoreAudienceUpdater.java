package com.launchcatch.campaign.contract;

import java.util.List;

/*
 * 광고 서빙이 00:00 에 집계한 가게별 대상 인원을 덮어쓰는 명령 인터페이스이다(추천 하루 예산의
 * 입력). 한 번 호출이 한 트랜잭션이다. 구현은 1000행을 넘기면 예외로 거부한다(#178,
 * qa-data-integrity-guideline.md 89행). 끊어서 여러 번 부르는 건 광고 서빙의 몫이다.
 */
public interface StoreAudienceUpdater {

    void overwrite(List<StoreAudienceCell> cells);
}
