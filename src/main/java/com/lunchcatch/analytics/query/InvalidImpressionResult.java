package com.lunchcatch.analytics.query;

import java.time.LocalDate;
import java.util.List;

/*
 * A-09 의 조회 결과다. summaryByReason 은 조회 조건 전체의 사유별 합계이고 items 는 그중 한 쪽의 항목이다.
 * 사유별 합계에는 조건이 허용하는 사유가 건수 0이어도 들어 있다.
 */
public record InvalidImpressionResult(
        LocalDate from,
        LocalDate to,
        List<ReasonTotal> summaryByReason,
        List<InvalidImpressionRow> items,
        int page,
        int size,
        long totalElements
) {
}
