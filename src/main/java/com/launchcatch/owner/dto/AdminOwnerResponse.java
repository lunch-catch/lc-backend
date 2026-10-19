package com.launchcatch.owner.dto;

import com.launchcatch.owner.entity.OwnerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record AdminOwnerResponse(
        @Schema(description = "점주 식별자입니다.", example = "7")
        long ownerId,
        @Schema(description = "상호입니다. 가게가 없으면 null이며 탈퇴 점주도 상호를 유지합니다.", example = "런치식당", nullable = true)
        String storeName,
        @Schema(description = "현재 점주 계정 상태입니다.")
        OwnerStatus status,
        @Schema(description = "가게 최종 등록 여부입니다. 가게가 없으면 false입니다.")
        boolean storeRegistered,
        @Schema(description = "사업자번호입니다. 일반 점주는 123-45-*****, 탈퇴 점주는 ***-**-*****이며 미등록 번호는 null입니다.", nullable = true)
        String businessNo,
        @Schema(description = "한국 시각 기준 가입 시각입니다.", example = "2026-10-01T00:00:00+09:00")
        OffsetDateTime joinedAt,
        @Schema(description = "한국 시각 기준 최근 로그인 시각입니다. 기록이 없거나 탈퇴 점주이면 null입니다.", nullable = true)
        OffsetDateTime lastLoginAt) { }
