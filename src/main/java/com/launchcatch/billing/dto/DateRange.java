package com.launchcatch.billing.dto;

import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.global.exception.CommonErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/*
 * 원장 조회의 기간이며 양 끝 날짜를 포함한다. 날짜는 모두 KST 기준이다.
 *
 * DB 의 날짜 함수를 쓰지 않으므로 "이 날짜의 행" 은 [시작일 00:00, 종료일 다음날 00:00) 범위
 * 조건으로 바꿔 질의한다. 그 변환을 이 타입이 한 곳에서 한다.
 * 기간이 비었거나 뒤집혔거나 허용 일수를 넘으면 모두 COMMON-002 로 거절한다.
 */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
    }

    // 일수 상한까지 함께 검증하며 만든다
    public static DateRange of(LocalDate from, LocalDate to, int maxDays) {
        DateRange range = new DateRange(from, to);
        if (range.days() > maxDays) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        return range;
    }

    // 양 끝을 포함한 일수다
    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    public LocalDateTime startInclusive() {
        return from.atStartOfDay();
    }

    public LocalDateTime endExclusive() {
        return to.plusDays(1).atStartOfDay();
    }

    // 기간의 모든 날짜를 오름차순으로 돌려준다
    public List<LocalDate> dates() {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates;
    }
}
