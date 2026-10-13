package com.launchcatch.billing.dto;

import com.launchcatch.billing.entity.PaymentStatus;

/*
 * 점주 화면에 보여 줄 결제 상태다. 내부 상태(특히 UNKNOWN)를 그대로 보여 주지 않는다.
 * UNKNOWN 은 "결과 알 수 없음" 이 아니라 "결제 확인 중" 으로 보인다. 내부 상태를 그대로 쓰는 곳은 관리자 화면뿐이다.
 */
public enum DisplayStatus {

    IN_PROGRESS,
    CONFIRMING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public static DisplayStatus of(PaymentStatus status) {
        return switch (status) {
            case REQUESTED -> IN_PROGRESS;
            case UNKNOWN -> CONFIRMING;
            case APPROVED -> COMPLETED;
            case FAILED -> FAILED;
            case CANCELLED -> CANCELLED;
        };
    }
}
