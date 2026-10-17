package com.launchcatch.analytics.batch;

/*
 * 한 영업일을 집계해 본 결과다. 지표의 result 라벨과 같은 뜻이다.
 */
public enum AggregationOutcome {
    /** 공개까지 마치고 실행 기록을 SUCCESS 로 닫았다. */
    SUCCEEDED,
    /** 실패로 닫고 그 날짜를 미처리로 남겼다. */
    FAILED,
    /** 정산 13:30 해제와 대사가 끝나지 않아 건너뛰고 미처리로 남겼다. */
    SOURCE_NOT_READY,
    /** 다른 서버나 수동 재계산이 잡고 있어 건너뛰었다. */
    BUSY,
    /** 도는 동안 실행 행을 다른 서버가 이어받아 멈췄다. 결과는 이어받은 쪽이 판정한다. */
    LEASE_LOST
}
