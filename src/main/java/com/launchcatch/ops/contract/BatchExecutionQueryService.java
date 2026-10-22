package com.launchcatch.ops.contract;

import java.time.LocalDate;
import java.util.List;

/** 배치 실행 기록을 공개한다. 분석의 운영 대시보드(A-06), 정산의 배치 조회가 쓴다. */
public interface BatchExecutionQueryService {

    /** 그 영업일에 실행된 모든 작업의 기록을 돌려준다. */
    List<BatchExecutionRecord> findByBusinessDate(LocalDate businessDate);

    /** 그 작업이 SUCCESS 로 끝난 영업일 가운데 [fromInclusive, toExclusive) 안의 것을 돌려준다. 분석이 미집계 날짜를 찾는 데 쓴다. */
    List<LocalDate> findSucceededBusinessDates(String jobName, LocalDate fromInclusive, LocalDate toExclusive);
}
