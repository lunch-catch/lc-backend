package com.launchcatch.ops.contract;

import java.time.LocalDate;
import java.util.List;

/** 배치 실행 기록을 공개한다. 분석의 운영 대시보드(A-06), 정산의 배치 조회가 쓴다. */
public interface BatchExecutionQueryService {

    /** 그 영업일에 실행된 모든 작업의 기록을 돌려준다. */
    List<BatchExecutionRecord> findByBusinessDate(LocalDate businessDate);
}
