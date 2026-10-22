package com.launchcatch.member.repository;

import com.launchcatch.member.contract.MemberStatus;
import java.time.LocalDateTime;

/*
 * 관리자 목록에 필요한 열만 담는다.
 * 엔티티를 그대로 읽으면 Member.profile(inverse OneToOne)이 행마다 따라 읽혀 N+1 이 되고,
 * provider_user_id 같은 내려가면 안 되는 값이 손에 닿는 곳에 놓인다.
 */
public record MemberAdminRow(
        Long memberId,
        String nickname,
        MemberStatus status,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt
) {
}
