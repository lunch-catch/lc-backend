package com.launchcatch.member.repository;

// 관리자 목록의 정렬 기준이다. 요청 값(joinedAt 등)을 여기로 옮기는 일은 서비스가 한다.
public enum MemberSortKey {

    JOINED_AT,
    LAST_LOGIN_AT,
    NICKNAME,
    MEMBER_ID
}
