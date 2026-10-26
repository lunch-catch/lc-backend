package com.launchcatch.billing.contract;

import java.util.UUID;

// 묶음 차감에서 판정이 커밋된 노출 1건과 그 판정이다
public record DeductionOutcome(UUID serveId, DeductionStatus status) {
}
