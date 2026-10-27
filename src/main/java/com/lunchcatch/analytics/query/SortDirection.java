package com.lunchcatch.analytics.query;

/*
 * sortDir 다.
 * 대소문자를 가리지 않고 asc 와 desc 만 받는다
 */
public enum SortDirection {
    ASC,
    DESC;

    public static SortDirection from(String parameter) {
        if ("asc".equalsIgnoreCase(parameter)) {
            return ASC;
        }
        if ("desc".equalsIgnoreCase(parameter)) {
            return DESC;
        }
        throw new IllegalArgumentException("지원하지 않는 정렬 방향입니다: " + parameter);
    }
}
