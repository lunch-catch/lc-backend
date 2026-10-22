package com.launchcatch.ops.contract;

import java.time.LocalDate;

/** 분석이 끝난 배치를 다시 돌릴 때 쓴다(#339). FAILED, SUCCESS 둘 다 된다. */
public interface BatchRerunner {

    /** 그 작업을 실행 중으로 되돌린다. RUNNING이면 아무것도 바꾸지 않고 false다. */
    boolean rerun(String jobName, LocalDate businessDate);
}
