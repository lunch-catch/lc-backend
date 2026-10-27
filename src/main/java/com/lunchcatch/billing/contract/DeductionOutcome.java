package com.lunchcatch.billing.contract;

import java.util.UUID;

/*
 * 묶음 차감에 넘긴 노출 1건의 결과다. 판정이 커밋됐으면 status 에 판정이 있고, 실패했으면 failed 이고 status 는 null 이다.
 * 실패를 DeductionStatus 에 섞지 않는다. 그 값은 deduction_result.billing_status 와 같은 판정이라 실패는 판정이 아니다.
 */
public record DeductionOutcome(UUID serveId, DeductionStatus status, boolean failed) {

    public DeductionOutcome {
        if (failed != (status == null)) {
            throw new IllegalArgumentException("실패한 결과만 판정이 없습니다. serveId=" + serveId);
        }
    }

    public static DeductionOutcome judged(UUID serveId, DeductionStatus status) {
        return new DeductionOutcome(serveId, status, false);
    }

    // 판정이 커밋되지 않은 노출이다. 릴레이가 다음 주기에 다시 넘긴다
    public static DeductionOutcome failed(UUID serveId) {
        return new DeductionOutcome(serveId, null, true);
    }
}
