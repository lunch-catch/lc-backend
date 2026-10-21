package com.launchcatch.billing;

// 결제 승인 확정, 웹훅과 재조회가 같은 제약 이름과 기본 실패 사유를 사용한다.
public final class PaymentConstants {

    public static final String PG_TID_UNIQUE = "uk_payment_pg_tid";
    public static final String FAILED_WITHOUT_REASON = "결제대행사 조회 결과 결제가 실패했습니다.";

    private PaymentConstants() {
    }
}
