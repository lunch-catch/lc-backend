package com.lunchcatch.member.dto;

import com.lunchcatch.member.contract.AgeGroup;
import com.lunchcatch.member.contract.Gender;
import com.lunchcatch.member.contract.MemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "회원 정보. 모든 시각은 한국 시각의 오프셋을 포함합니다.")
public record MemberResponse(
        @Schema(description = "회원 식별자입니다.", example = "1024")
        Long memberId,
        @Schema(description = "회원 닉네임입니다.", example = "점심헌터")
        String nickname,
        @Schema(description = "카카오에서 받은 읽기 전용 프로필 이미지 주소. 값이 없으면 null입니다.", example = "https://example.com/profile.png", nullable = true)
        String profileImageUrl,
        @Schema(description = "회원 상태입니다.", example = "ACTIVE")
        MemberStatus status,
        @Schema(description = "온보딩 완료 여부입니다.", example = "true")
        boolean onboardingCompleted,
        @Schema(description = "온보딩 완료와 위치정보 수집 동의가 모두 참일 때만 참입니다.", example = "true")
        boolean feedAvailable,
        @Schema(description = "성별과 연령대. 온보딩 완료 전에는 null입니다.")
        Profile profile,
        @Schema(description = "알림·위치 동의 상태와 동의 시각입니다.")
        Consents consents,
        @Schema(description = "최초 가입 시각입니다.", example = "2026-10-01T09:00:00+09:00")
        OffsetDateTime createdAt
) {
    @Schema(name = "MemberProfileResponse", description = "온보딩에서 확정한 프로필. 온보딩 완료 전에는 null입니다.", types = {"object", "null"})
    public record Profile(
            @Schema(description = "성별입니다.", example = "FEMALE") Gender gender,
            @Schema(description = "연령대입니다.", example = "AGE_20S") AgeGroup ageGroup) {
    }

    @Schema(name = "MemberConsentsResponse", description = "현재 동의 상태. 동의하지 않았으면 해당 동의 시각은 null입니다.")
    public record Consents(
            @Schema(description = "알림 수신 동의 여부입니다.", example = "false")
            boolean notificationOptIn,
            @Schema(description = "알림 수신 동의 시각입니다.", example = "2026-10-07T09:00:00+09:00", nullable = true)
            OffsetDateTime notificationOptedInAt,
            @Schema(description = "위치정보 수집 동의 여부입니다.", example = "true")
            boolean locationOptIn,
            @Schema(description = "위치정보 수집 동의 시각입니다.", example = "2026-10-07T09:00:00+09:00", nullable = true)
            OffsetDateTime locationOptedInAt
    ) {
    }
}
