package com.launchcatch.billing.dto;

/**
 * 그날 계획에서 하루 동안 바뀌지 않는 세 값이다. 장중에 바뀌는 예약액과 중단 여부는 담지 않는다.
 *
 * @param planId    계획 행 ID. 시간대별 목표를 찾는 키
 * @param ownerId   원장에 적을 점주
 * @param unitPrice 노출 단가
 */
public record PlanSnapshot(Long planId, Long ownerId, long unitPrice) {
}
