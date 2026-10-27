package com.lunchcatch.adserving.impression.service;

import com.lunchcatch.adserving.impression.entity.ImpressionLog;
import java.time.LocalDateTime;

/*
 * 판정을 마치고 저장을 기다리는 노출 1건이다(노출 수집 v3).
 * receivedAt 은 요청이 받은 한국 시각이다. 저장 시각(created_at)과 영업일이 이 값에서 나온다(#783).
 * campaignMismatch 는 요청의 캠페인이 serve 기록과 달랐던 유효 노출이다. 새로 저장됐을 때만 센다.
 */
public record QueuedImpression(ImpressionLog log, LocalDateTime receivedAt, boolean campaignMismatch) {
}
