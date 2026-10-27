package com.lunchcatch.member.dto;

import com.lunchcatch.member.contract.MemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

// provider_user_id 는 어떤 필드로도 내리지 않는다
@Schema(description = "관리자 회원 목록 항목. 탈퇴 회원은 비식별 처리된 닉네임을 표시하고 마지막 로그인 시각은 null입니다.")
public record AdminMemberResponse(
        @Schema(description = "회원 식별자입니다.", example = "1024")
        Long memberId,
        @Schema(description = "닉네임입니다. 탈퇴 회원은 탈퇴한 회원으로 표시합니다.", example = "점심헌터")
        String nickname,
        @Schema(description = "회원 상태입니다.", example = "ACTIVE")
        MemberStatus status,
        @Schema(description = "최초 가입 시각. 한국 시각의 오프셋을 포함합니다.", example = "2026-10-01T09:00:00+09:00")
        OffsetDateTime joinedAt,
        @Schema(description = "마지막 로그인 시각. 기록이 없거나 탈퇴한 회원이면 null입니다.", example = "2026-10-13T09:00:00+09:00", nullable = true)
        OffsetDateTime lastLoginAt
) {
}
