package com.launchcatch.member.dto;

// sortBy 요청 값의 허용 목록이다. 리포지토리 정렬 기준으로의 변환은 서비스가 맡는다(dto 가 repository 를 참조하지 않는다). 요청 값은 응답 필드 이름(joinedAt 등)을 그대로 쓴다.
public enum AdminMemberSortBy {

    JOINED_AT("joinedAt"),
    LAST_LOGIN_AT("lastLoginAt"),
    NICKNAME("nickname"),
    MEMBER_ID("memberId");

    private final String requestValue;

    AdminMemberSortBy(String requestValue) {
        this.requestValue = requestValue;
    }

    public static AdminMemberSortBy from(String requestValue) {
        for (AdminMemberSortBy candidate : values()) {
            if (candidate.requestValue.equals(requestValue)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unsupported sortBy: " + requestValue);
    }
}
