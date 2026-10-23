package com.launchcatch.billing.dto;

/**
 * 그날 계획에서 하루 동안 바뀌지 않는 두 값이다. 장중에 바뀌는 예약액과 중단 여부는 담지 않는다.
 *
 * @param ownerId   원장에 적을 점주
 * @param unitPrice 노출 단가
 */
public record PlanSnapshot(Long ownerId, long unitPrice) {
}
