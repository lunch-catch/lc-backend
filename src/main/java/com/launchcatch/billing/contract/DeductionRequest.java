package com.launchcatch.billing.contract;

import java.time.LocalDate;
import java.util.UUID;

// 묶음 차감에 넘기는 유효 노출 1건이며 뜻은 단건 deduct 의 세 인자와 같다
public record DeductionRequest(UUID serveId, Long campaignId, LocalDate businessDate) {
}
