package com.lunchcatch.member.dto;

import com.lunchcatch.member.contract.MemberStatus;
import java.time.LocalDate;

/*
 * 관리자 사용자 목록 요청이다. 값이 null 인 조건은 빠지고 sortBy, sortDir 이 null 이면 기본값(joinedAt, desc)을 쓴다.
 * keyword 는 닉네임 접두사로만 해석한다. 숫자로 된 검색어도 닉네임이다.
 */
public record AdminMemberListQuery(
        MemberStatus status,
        LocalDate joinedFrom,
        LocalDate joinedTo,
        String keyword,
        int page,
        int size,
        String sortBy,
        String sortDir
) {
}
