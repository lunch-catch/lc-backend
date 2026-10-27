package com.launchcatch.billing.cache;

import java.time.LocalDate;
import java.util.UUID;

// 당일 소진 해시에 판정을 맡길 노출 하나다. 묶음 판정과 묶음 되돌리기가 받는다
public record SpendDeduction(LocalDate businessDate, Long campaignId, UUID serveId, long unitPrice) {
}
