package com.launchcatch.billing.dto;

import java.time.LocalDate;

/*
 * 합계 한 줄에 해당하는 구간이다. start 와 end 는 요청 기간 안으로 잘린 양 끝 날짜다.
 * 일부만 걸친 주와 월은 그 안의 날짜만 합산하기 때문에 label 의 날짜와 start 가 다를 수 있다.
 */
public record PeriodBucket(String label, LocalDate start, LocalDate end) {

    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
    }
}
