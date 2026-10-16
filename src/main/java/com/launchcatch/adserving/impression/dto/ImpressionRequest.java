package com.launchcatch.adserving.impression.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;

/*
 * 요청한 사용자는 본문이 아니라 로그인 정보로 정한다.
 * serveId 를 UUID 타입으로 받으면 형식 오류가 본문 해석 실패(COMMON-003)로 가서, 명세의 COMMON-002 와 맞추려고 문자열로 받아 검사한다.
 */
public record ImpressionRequest(
        @Schema(description = "피드 응답의 카드 식별자", example = "01925f3a-7c1e-7b2a-9d4e-3f5a6b7c8da0")
        @NotNull
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String serveId,
        @Schema(description = "카드의 캠페인", example = "101")
        @NotNull
        Long campaignId,
        @Schema(description = "카드가 보인 시각", example = "2026-10-01T10:15:31.880+09:00")
        @NotNull
        OffsetDateTime displayedAt,
        @Schema(description = "카드가 보인 시점의 위도. 서버는 소수점 3자리로 반올림해 저장한다", example = "37.4979")
        @NotNull
        Double latitude,
        @Schema(description = "카드가 보인 시점의 경도. 서버는 소수점 3자리로 반올림해 저장한다", example = "127.0276")
        @NotNull
        Double longitude,
        @Schema(description = "카드를 본 시간(밀리초). 참고 지표", example = "2140", nullable = true)
        @PositiveOrZero
        Integer viewedMs
) {
}
