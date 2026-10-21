package com.launchcatch.coupon.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CouponErrorCode implements ErrorCode {

    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "COUPON-001", "쿠폰을 찾을 수 없습니다."),
    COUPON_NOT_AVAILABLE(HttpStatus.CONFLICT, "COUPON-002", "현재 이용할 수 없는 쿠폰입니다."),
    MEMBER_ACCOUNT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "COUPON-003", "쿠폰을 이용할 수 없는 계정입니다."),
    CAMPAIGN_NOT_WISHED(HttpStatus.FORBIDDEN, "COUPON-004", "해당 캠페인을 찜한 뒤 발급할 수 있습니다."),
    EVENT_ALREADY_ISSUED(HttpStatus.CONFLICT, "COUPON-005", "오늘 이 이벤트에서 이미 쿠폰을 발급받았습니다."),
    INVENTORY_EXHAUSTED(HttpStatus.CONFLICT, "COUPON-006", "쿠폰이 모두 소진되었습니다."),
    DAILY_LIMIT_REACHED(HttpStatus.CONFLICT, "COUPON-007", "오늘 발급 가능한 쿠폰 수를 모두 사용했습니다."),
    ISSUANCE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "COUPON-008", "발급 요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요."),
    LOCATION_NOT_ALLOWED(HttpStatus.FORBIDDEN, "COUPON-009", "현재 위치에서 사용할 수 없습니다."),
    QR_NOT_VALID(HttpStatus.CONFLICT, "COUPON-010", "QR이 만료되었거나 유효하지 않습니다."),
    COUPON_ACCESS_DENIED(HttpStatus.FORBIDDEN, "COUPON-011", "해당 쿠폰에 접근할 권한이 없습니다."),
    COUPON_ALREADY_USED(HttpStatus.CONFLICT, "COUPON-012", "이미 사용된 쿠폰입니다."),
    OWNER_ACCOUNT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "COUPON-013", "쿠폰을 사용할 수 없는 점주 계정입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
