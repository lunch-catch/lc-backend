package com.launchcatch.member.dto;

import com.launchcatch.member.contract.MemberStatus;
import java.time.LocalDate;

/*
 * 관리자 사용자 목록 요청이다. 값이 null 인 조건은 빠지고 sortBy, sortDir 이 null 이면 기본값(joinedAt, desc)을 쓴다.
 * keyword 는 searchType 이 가리키는 대상으로만 해석한다. 값의 모양으로 추측하지 않는다.
 */
public record AdminMemberListQuery(
        MemberStatus status,
        LocalDate joinedFrom,
        LocalDate joinedTo,
        AdminMemberSearchType searchType,
        String keyword,
        int page,
        int size,
        String sortBy,
        String sortDir
) {
}
