package com.launchcatch.coupon.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.coupon.dto.CouponUsageHistoryResponse;
import com.launchcatch.coupon.dto.CouponUseRequest;
import com.launchcatch.coupon.dto.CouponUseResponse;
import com.launchcatch.coupon.dto.OwnerCouponDailyUsageResponse;
import com.launchcatch.coupon.dto.OwnerCouponSummaryResponse;
import com.launchcatch.coupon.service.OwnerCouponService;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner")
@RequiredArgsConstructor
@Tag(name = "점주 쿠폰", description = "점주가 쿠폰 사용을 확정하고 사용 이력과 캠페인 수량을 조회한다.")
public class OwnerCouponController {

    private final OwnerCouponService service;

    @GetMapping("/campaigns/{campaignId}/coupons/summary")
    @Operation(summary = "캠페인 쿠폰 수량 조회",
            description = "한국 날짜 기준 양끝을 포함해 최대 31일의 전체·누적 발급·사용·미발급 수량을 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수량 조회 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 잘못된 ID 또는 기간. COMMON-003: 날짜 누락 또는 형식 오류"),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 인증 필요"),
            @ApiResponse(responseCode = "403", description = "AUTH-006: OWNER 역할 또는 ACTIVE 상태가 아님. COUPON-011: 캠페인이 없거나 본인 소유가 아님")
    })
    public ResponseEnvelope<OwnerCouponSummaryResponse> summary(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner,
            @PathVariable @Positive long campaignId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEnvelope.success(service.summary(owner.getId(), campaignId, from, to));
    }

    // 시연 영상 촬영을 위해 기존 100m 제한을 임시로 30km로 늘렸으며 촬영 후 설명도 100m로 복원한다.
    @PostMapping("/coupon-usages")
    @Operation(summary = "쿠폰 QR 사용", description = "현재 매장 관리 권한과 30km 거리, 계정·QR·사용 시간을 확인하고 할인액과 사용 이력을 확정한다.")
    @ApiResponse(responseCode = "201", description = "쿠폰과 사용 이력을 같은 트랜잭션으로 확정한 결과다.",
            useReturnTypeSchema = true)
    public ResponseEntity<ResponseEnvelope<CouponUseResponse>> redeem(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody CouponUseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseEnvelope.success(service.redeem(user.getId(), request)));
    }

    @GetMapping("/stores/{storeId}/coupon-usages/daily")
    @Operation(summary = "매장의 날짜별 쿠폰 사용 건수 조회",
            description = "본인 매장의 모든 캠페인에서 사용한 쿠폰을 실제 한국 사용일로 합산한다. 양끝을 포함해 최대 31일이다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "날짜별 사용 건수와 합계를 반환한다.", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 잘못된 ID 또는 기간. COMMON-003: 날짜 누락 또는 형식 오류"),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 인증 필요"),
            @ApiResponse(responseCode = "403", description = "AUTH-006: OWNER 역할 또는 ACTIVE 상태가 아님. COUPON-011: 매장 관리 권한이 없음")
    })
    public ResponseEnvelope<OwnerCouponDailyUsageResponse> getDailyUsages(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner,
            @PathVariable @Positive long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEnvelope.success(service.getDailyUsages(owner.getId(), storeId, from, to));
    }

    // 매장의 사용 이력을 한국 날짜 범위와 커서로 조회한다.
    @GetMapping("/stores/{storeId}/coupon-usages")
    @Operation(summary = "매장 쿠폰 사용 이력 조회", description = "ACTIVE 점주가 한국 날짜 범위의 확정 사용 이력을 최신순으로 조회한다.")
    @ApiResponse(responseCode = "200", description = "쿠폰 사용 이력과 다음 페이지 토큰을 반환한다.",
            useReturnTypeSchema = true)
    public ResponseEnvelope<CursorPageResponse<CouponUsageHistoryResponse>> getUsages(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "사용 이력을 조회할 매장 ID다.", example = "10")
            @PathVariable @Positive Long storeId,
            @Parameter(description = "조회 시작일이며 당일 00:00을 포함한다.", example = "2026-09-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일이며 다음 날 00:00은 제외한다.", example = "2026-09-30")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "한 페이지의 이력 수다.",
                    schema = @Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1", maximum = "100"))
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @Parameter(description = "같은 매장과 조회 기간의 nextPageToken이다. 첫 조회나 조건 변경 시 생략한다.")
            @RequestParam(required = false) String pageToken) {
        return ResponseEnvelope.success(service.getUsages(user.getId(), storeId, from, to, pageSize, pageToken));
    }
}
