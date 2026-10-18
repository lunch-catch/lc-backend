package com.launchcatch.store;

import com.launchcatch.store.contract.OwnerStoreSnapshot.BusinessHour;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.List;

public final class StoreBusinessHours {
    private StoreBusinessHours() { }

    // 요청에 없는 요일은 휴무로 채우며 저장할 일주일 전체를 검증한다.
    public static List<BusinessHour> normalize(List<BusinessHour> hours) {
        if (hours == null || hours.size() > DayOfWeek.values().length) {
            throw new IllegalArgumentException("영업시간 목록은 필수이며 최대 7개입니다.");
        }
        var days = new EnumMap<DayOfWeek, BusinessHour>(DayOfWeek.class);
        for (var hour : hours) {
            if (hour == null || hour.dayOfWeek() == null) {
                throw new IllegalArgumentException("영업시간 항목과 요일은 필수입니다.");
            }
            var day = DayOfWeek.valueOf(hour.dayOfWeek());
            if (days.putIfAbsent(day, hour) != null) {
                throw new IllegalArgumentException("같은 요일은 한 번만 보낼 수 있습니다.");
            }
        }
        for (var day : DayOfWeek.values()) {
            days.putIfAbsent(day, new BusinessHour(day.name(), null, null, true, null, null, null));
        }
        var normalized = List.copyOf(days.values());
        if (!isComplete(normalized)) {
            throw new IllegalArgumentException("영업시간의 필수 시각과 시간 관계를 확인해 주세요.");
        }
        return normalized;
    }

    // 저장과 조회가 같은 기준으로 일주일 전체의 유효성을 판단한다.
    public static boolean isComplete(List<BusinessHour> hours) {
        if (hours == null || hours.size() != DayOfWeek.values().length) {
            return false;
        }
        var days = EnumSet.noneOf(DayOfWeek.class);
        for (var hour : hours) {
            if (hour == null || hour.dayOfWeek() == null || !validTimes(hour)) {
                return false;
            }
            DayOfWeek day;
            try {
                day = DayOfWeek.valueOf(hour.dayOfWeek());
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (!days.add(day)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validTimes(BusinessHour hour) {
        if (hour.isClosed()) {
            return hour.openTime() == null && hour.closeTime() == null && hour.breakStartTime() == null
                    && hour.breakEndTime() == null && hour.lastOrderTime() == null;
        }
        var open = hour.openTime();
        var close = hour.closeTime();
        if (open == null || close == null || !open.isBefore(close) || !wholeSecond(open) || !wholeSecond(close)) {
            return false;
        }
        var start = hour.breakStartTime();
        var end = hour.breakEndTime();
        if (start != null || end != null) {
            if (start == null || end == null || !wholeSecond(start) || !wholeSecond(end)
                    || !open.isBefore(start) || !start.isBefore(end) || !end.isBefore(close)) {
                return false;
            }
        }
        var last = hour.lastOrderTime();
        return last == null || wholeSecond(last) && !last.isBefore(open) && !last.isAfter(close)
                && (start == null || last.isBefore(start) || !last.isBefore(end));
    }

    private static boolean wholeSecond(LocalTime time) {
        return time.getNano() == 0;
    }
}
