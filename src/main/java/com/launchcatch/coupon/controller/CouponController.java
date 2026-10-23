package com.launchcatch.coupon.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponEventStatusRequest;
import com.launchcatch.coupon.dto.CouponEventStatusResponse;
import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.dto.CouponIssueRequest;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.dto.CouponQrResponse;
import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.service.CouponCommandService;
import com.launchcatch.coupon.service.CouponLookUpService;
import com.launchcatch.global.logging.AccessLogSignal;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "쿠폰", description = "쿠폰 관련 API")
public class CouponController {

    private final CouponLookUpService couponLookUpService;
    private final CouponCommandService couponCommandService;

    // 인증된 회원에게 요청한 매장의 오늘 이벤트와 발급 가능 상태를 입력 순서대로 반환한다.
    @PostMapping("/coupon-events:getStatus")
    @Operation(summary = "매장별 쿠폰 이벤트 상태 조회", description = "오늘의 이벤트와 발급 가능 상태를 일괄 조회한다. 찜 조회 연결 전에는 wished와 canIssue가 false이며 매장 존재 검증은 후속으로 연결한다.")
    @ApiResponse(responseCode = "200", description = "서버 시각과 매장별 상태를 반환한다.", useReturnTypeSchema = true)
    public ResponseEnvelope<CouponEventStatusResponse> getEventStatus(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody CouponEventStatusRequest request) {
        return ResponseEnvelope.success(couponLookUpService.getEventStatus(user.getId(), request.storeIds()));
    }

    @PostMapping("/coupons")
    @Operation(summary = "선착순 쿠폰 발급", description = "발급 가능한 재고와 일일 한도를 확인하고 회원 쿠폰을 발급한다.")
    @ApiResponse(responseCode = "201", description = "발급과 재고 변경, 일일 횟수 반영을 완료한 결과다.", useReturnTypeSchema = true)
    public DeferredResult<ResponseEntity<ResponseEnvelope<CouponIssueResponse>>> issueCoupon(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody CouponIssueRequest request, HttpServletRequest httpRequest) {
        DeferredResult<ResponseEntity<ResponseEnvelope<CouponIssueResponse>>> result = new DeferredResult<>(0L);
        couponCommandService.issueAsync(user.getId(), request.eventId()).whenComplete((issued, error) -> {
            if (error != null) {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                result.setErrorResult(cause);
            } else {
                AccessLogSignal.markExpected(httpRequest);
                result.setResult(ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(issued)));
            }
        });
        return result;
    }

    // 본인 쿠폰의 QR을 생성하거나 갱신하고 응답 캐시를 금지한다.
    @PostMapping("/coupons/{memberCouponId}:generateQr")
    @Operation(summary = "쿠폰 QR 생성·갱신", description = "사용 가능한 본인 쿠폰의 QR을 교체한다. 이전 QR은 즉시 무효화된다.")
    @ApiResponse(responseCode = "200", description = "QR 이미지와 토큰 만료 시각을 반환한다.",
            useReturnTypeSchema = true)
    public ResponseEntity<ResponseEnvelope<CouponQrResponse>> generateQr(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "QR을 생성할 본인 쿠폰 ID다.", example = "1001",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1"))
            @PathVariable @Positive Long memberCouponId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResponseEnvelope.success(couponCommandService.generateQr(user.getId(), memberCouponId)));
    }

    // 인증된 회원의 쿠폰을 필터와 커서로 조회한다.
    @GetMapping("/coupons")
    @Operation(summary = "내 쿠폰 목록 조회", description = "삭제된 쿠폰을 제외하고 최신 발급순으로 조회한다.")
    @ApiResponse(responseCode = "200", description = "쿠폰 목록과 다음 페이지 토큰을 반환한다.",
            useReturnTypeSchema = true)
    public ResponseEnvelope<CursorPageResponse<CouponDetailResponse>> getCoupons(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "상태나 사용 가능 여부로 필터링한다. 생략하면 전체 조회한다.",
                    schema = @Schema(allowableValues = {"usable = true", "state = \"ISSUED\"", "state = \"USED\"", "state = \"EXPIRED\""}))
            @RequestParam(required = false) String filter,
            @Parameter(description = "한 페이지에 조회할 쿠폰 수다.",
                    schema = @Schema(type = "integer", format = "int32", defaultValue = "20", minimum = "1", maximum = "100"))
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @Parameter(description = "이전 응답의 nextPageToken이다. 첫 조회나 필터 변경 시 생략한다.")
            @RequestParam(required = false) String pageToken) {
        return ResponseEnvelope.success(couponLookUpService.getCoupons(user.getId(), filter, pageSize, pageToken));
    }

    /**
     * 인증된 회원의 오늘 일일 발급 횟수와 일일 발급 한도를 조회한다.
     *
     * @param user 인증된 회원 정보
     * @return 영업일과 발급 횟수, 일일 한도 및 한도 도달 여부를 담은 성공 응답
     */
    @GetMapping("/coupons/issuance-limit")
    @Operation(summary = "당일 쿠폰 발급 한도 조회", description = "한국 날짜 기준으로 오늘의 발급 수와 일일 한도를 조회한다.")
    @ApiResponse(responseCode = "200", description = "당일 발급 한도 정보를 반환한다.",
            useReturnTypeSchema = true)
    public ResponseEnvelope<CouponIssuanceLimitResponse> getIssuanceLimit(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(couponLookUpService.getIssuanceLimit(user.getId()));
    }

    /**
     * 인증된 회원의 누적 쿠폰 발급 수와 사용 수, 할인받은 금액의 합계를 조회한다.
     *
     * @param user 인증된 회원 정보
     * @return 누적 발급 수와 사용 수, 할인액 합계를 담은 성공 응답
     */
    @GetMapping("/coupons/summary")
    @Operation(summary = "마이페이지 쿠폰 요약 조회", description = "누적 발급 수와 사용 수, 할인액 합계를 조회한다. 삭제한 쿠폰도 발급 수에 포함한다.")
    @ApiResponse(responseCode = "200", description = "누적 쿠폰 통계를 반환한다.",
            useReturnTypeSchema = true)
    public ResponseEnvelope<CouponSummaryResponse> getSummary(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(couponLookUpService.getSummary(user.getId()));
    }

    /**
     * 본인 쿠폰의 스냅샷과 조회 시점 상태, 사용 가능 여부 및 사용 결과를 조회한다.
     *
     * @param user 인증된 회원 정보
     * @param memberCouponId 조회할 회원 쿠폰의 ID
     * @return 쿠폰의 상세 표시 정보와 사용 결과를 담은 성공 응답
     */
    @GetMapping("/coupons/{memberCouponId}")
    @Operation(summary = "내 쿠폰 단건 조회", description = "본인 쿠폰의 혜택과 사용 상태를 조회한다.")
    @ApiResponse(responseCode = "200", description = "쿠폰 상세 정보를 반환한다.",
            useReturnTypeSchema = true)
    public ResponseEnvelope<CouponDetailResponse> getDetail(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "조회할 본인 쿠폰 ID다.", example = "1001",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1"))
            @PathVariable @Positive Long memberCouponId) {
        return ResponseEnvelope.success(couponLookUpService.getDetail(user.getId(), memberCouponId));
    }

    /**
     * 본인의 미사용 쿠폰을 소프트 삭제하고 기존 QR을 무효화한다.
     *
     * @param user 인증된 회원 정보
     * @param memberCouponId 삭제할 회원 쿠폰의 ID
     * @return 응답 본문 없는 HTTP 204 응답
     */
    @DeleteMapping("/coupons/{memberCouponId}")
    @Operation(summary = "미사용 쿠폰 삭제", description = "미사용·만료 쿠폰을 삭제한다. 발급일의 횟수는 처음 삭제할 때만 1 줄인다.")
    @ApiResponse(responseCode = "204", description = "재삭제를 포함해 성공 시 본문 없이 응답한다.", content = @Content)
    public ResponseEntity<Void> deleteCoupon(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Parameter(description = "삭제할 본인 쿠폰 ID다.", example = "1001",
                    schema = @Schema(type = "integer", format = "int64", minimum = "1"))
            @PathVariable @Positive Long memberCouponId) {
        couponCommandService.deleteCoupon(user.getId(), memberCouponId);
        return ResponseEntity.noContent().build();
    }

}
