package com.launchcatch.adserving.feed.dto;

import com.launchcatch.adserving.feed.entity.SwipeAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

/*
 * 요청한 사용자는 본문이 아니라 로그인 정보로 정한다.
 * serveId 와 action 을 UUID 와 enum 으로 받으면 형식 오류가 본문 해석 실패(COMMON-003)로 가서, 명세의 COMMON-002 와 맞추려고 문자열로 받아 검사한다.
 */
public record SwipeRequest(
        @Schema(description = "피드 응답의 카드 식별자", example = "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0")
        @NotNull
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String serveId,
        @Schema(description = "카드의 캠페인", example = "101")
        @NotNull
        Long campaignId,
        @Schema(description = "WISH(찜) 또는 PASS(패스)", allowableValues = {"WISH", "PASS"})
        @NotNull
        @Pattern(regexp = "^(WISH|PASS)$")
        String action,
        @Schema(description = "클라이언트에서 넘긴 시각", example = "2026-10-01T10:15:34.020+09:00")
        @NotNull
        OffsetDateTime occurredAt
) {

    public SwipeAction swipeAction() {
        return SwipeAction.valueOf(action);
    }
}
