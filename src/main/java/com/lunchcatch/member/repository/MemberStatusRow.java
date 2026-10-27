package com.lunchcatch.member.repository;

import com.lunchcatch.member.contract.MemberStatus;

// 프로필을 읽지 않고 회원 ID 와 현재 상태만 담는다.
public record MemberStatusRow(Long memberId, MemberStatus status) {
}
