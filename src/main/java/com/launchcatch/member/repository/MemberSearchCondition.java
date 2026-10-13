package com.launchcatch.member.repository;

import com.launchcatch.member.contract.MemberStatus;
import java.time.LocalDateTime;

/*
 * 관리자 목록의 검색 조건이다. 값이 null 인 항목은 조건에서 빠진다.
 * 가입 범위는 [createdFrom, createdBefore) 이고 저장 시각이 이미 KST 라 변환 없이 그대로 비교한다.
 */
public record MemberSearchCondition(
        MemberStatus status,
        LocalDateTime createdFrom,
        LocalDateTime createdBefore,
        String nicknamePrefix
) {
}
