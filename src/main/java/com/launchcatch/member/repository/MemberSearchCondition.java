package com.launchcatch.member.repository;

import com.launchcatch.member.contract.MemberStatus;
import java.time.LocalDateTime;

/*
 * 관리자 목록의 검색 조건이다. 값이 null 인 항목은 조건에서 빠진다.
 * 가입 범위는 [createdFrom, createdBefore) 이고 저장 시각이 이미 KST 라 변환 없이 그대로 비교한다.
 * memberId 와 nicknamePrefix 는 서비스가 keyword 를 가른 결과이며 한 번에 하나만 채워진다.
 */
public record MemberSearchCondition(
        MemberStatus status,
        LocalDateTime createdFrom,
        LocalDateTime createdBefore,
        Long memberId,
        String nicknamePrefix
) {
}
