package com.launchcatch.member.dto;

import com.launchcatch.member.contract.MemberStatus;
import java.time.LocalDate;

/*
 * 관리자 사용자 목록 요청이다. 값이 null 인 항목은 조건에서 빠지고 sortBy, sortDir 은 기본값을 쓴다.
 * 페이지 크기는 20건 고정이라 요청에 없다(V51 11행).
 */
public record AdminMemberListQuery(
        MemberStatus status,
        LocalDate joinedFrom,
        LocalDate joinedTo,
        AdminMemberSearchType searchType,
        String keyword,
        int page,
        String sortBy,
        String sortDir
) {
}
