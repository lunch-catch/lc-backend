package com.lunchcatch.ops.contract;

import java.time.LocalDate;

/** 분석이 끝난 배치를 다시 돌릴 때 쓴다(#339). FAILED, SUCCESS 둘 다 된다. */
public interface BatchRerunner {

    /** 그 작업을 실행 중으로 되돌린다. RUNNING이면 아무것도 바꾸지 않고 false다. */
    boolean rerun(String jobName, LocalDate businessDate);

    /**
     * rerun 과 같이 되돌리되 이 서버가 돌리지 않고 배치 서버의 이어받기가 집게 한다(#1238).
     * RUNNING이면 아무것도 바꾸지 않고 false다.
     */
    boolean requestRerun(String jobName, LocalDate businessDate);
}
