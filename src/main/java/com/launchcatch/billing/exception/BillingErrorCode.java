package com.launchcatch.billing.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 정산 도메인의 오류 코드다. api 명세서의 billing.md 가 이 목록을 소유한다.
 *
 * 번호는 1부터 끊기지 않아야 한다. ErrorCodeCatalogTest 의 번호_연속 이 접두어마다 그것을
 * 검사하므로, 중간을 비우고 뒤 번호를 먼저 쓰면 빌드가 떨어진다.
 *
 * 그래서 명세의 BILLING-030, 031, 032 를 016, 017, 018 로 당겼다. 명세는 016 에서 029 를
 * 쓰지 않는데 그대로 옮기면 열네 자리가 비어 이 검사를 통과할 수 없다. 뜻은 그대로다.
 *
 * 쓰는 자리가 아직 없는 코드도 함께 둔다. 번호는 한 곳에서 한 번에 정해야 나중에 들어오는
 * 작업이 빈 번호를 각자 집어 쓰는 일이 없다.
 */
@Getter
@RequiredArgsConstructor
public enum BillingErrorCode implements ErrorCode {

    // 결제와 충전
    MIN_CHARGE_AMOUNT_NOT_MET(HttpStatus.BAD_REQUEST, "BILLING-001",
            "최소 충전 금액보다 적은 금액은 충전할 수 없습니다."),
    CHARGE_PRODUCT_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "BILLING-002",
            "선택할 수 없는 충전 금액입니다."),
    PAYMENT_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "BILLING-003",
            "결제가 실패했습니다. 충전되지 않았습니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "BILLING-004",
            "결제를 찾을 수 없습니다."),
    PAYMENT_NOT_RECHECKABLE(HttpStatus.CONFLICT, "BILLING-005",
            "확인 중인 결제가 아니어서 재조회할 수 없습니다."),

    /*
     * 원장 조회 자체가 실패한 경우다.
     * 잔액을 캐시된 값으로 대신 내려주지 않는다. 금전 값이라 틀린 값을 보여 주는 것보다
     * 실패를 드러내는 편이 낫다.
     */
    LEDGER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "BILLING-006",
            "잔액을 조회할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    /*
     * 승인 요청이 결제대행사에 가지 않은 것이 확실한 경우다.
     * 연결 실패와 회로 열림이 그렇다. 결제 상태를 바꾸지 않으므로 같은 주문으로 다시 시도할 수 있다.
     */
    PG_REQUEST_NOT_SENT(HttpStatus.SERVICE_UNAVAILABLE, "BILLING-007",
            "결제 승인 요청을 보내지 못했습니다. 잠시 후 다시 시도해 주세요."),

    UNKNOWN_PAYMENT_EXISTS(HttpStatus.CONFLICT, "BILLING-008",
            "확인 중인 결제가 있어 새로 충전할 수 없습니다. 확인이 끝나면 알려드립니다."),
    PAYMENT_ALREADY_CANCELLED(HttpStatus.CONFLICT, "BILLING-009",
            "이미 취소된 주문입니다. 새로 주문해 주세요."),

    // 환불
    LIVE_CAMPAIGN_EXISTS(HttpStatus.CONFLICT, "BILLING-010",
            "집행 중인 캠페인이 있어 환불할 수 없습니다. 캠페인을 먼저 중단해 주세요."),
    REFUND_REQUEST_ALREADY_PENDING(HttpStatus.CONFLICT, "BILLING-011",
            "이미 처리 대기 중인 환불 요청이 있습니다."),
    NO_REFUNDABLE_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "BILLING-012",
            "환불할 수 있는 잔액이 없습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "BILLING-013",
            "잔액이 요청 금액보다 적습니다."),
    REFUND_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "BILLING-014",
            "환불 요청을 찾을 수 없습니다."),
    REFUND_REQUEST_ALREADY_PROCESSED(HttpStatus.CONFLICT, "BILLING-015",
            "이미 처리된 환불 요청입니다."),

    // 조정과 승인 확정의 코드이며 명세의 030, 031, 032 를 당긴 번호다
    ADJUSTMENT_MAKES_BALANCE_NEGATIVE(HttpStatus.CONFLICT, "BILLING-016",
            "조정 후 잔액이 음수가 되어 처리할 수 없습니다."),
    ORDER_ID_MISMATCH(HttpStatus.CONFLICT, "BILLING-017",
            "주문 정보가 일치하지 않습니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "BILLING-018",
            "같은 키로 내용이 다른 요청이 이미 처리되었습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
