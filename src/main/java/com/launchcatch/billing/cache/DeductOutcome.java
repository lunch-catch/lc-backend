package com.launchcatch.billing.cache;

import com.launchcatch.billing.contract.DeductionStatus;

/*
 * 당일 소진 해시의 판정 결과다. 판정이 났거나 해시가 없어 판정하지 못했거나 둘 중 하나다.
 *
 * 해시가 없다는 것은 오류가 아니라 재구성할 때라는 신호라서 DeductionStatus 에 값을 더하지 않고 따로 나타낸다.
 * contract 의 enum 은 광고 서빙에 알린 계약이라 이 내부 신호로 늘리지 않는다.
 */
public record DeductOutcome(DeductionStatus status) {

    private static final DeductOutcome MISSING = new DeductOutcome(null);

    public static DeductOutcome of(DeductionStatus status) {
        return new DeductOutcome(status);
    }

    public static DeductOutcome hashMissing() {
        return MISSING;
    }

    public boolean isHashMissing() {
        return status == null;
    }
}
