package com.launchcatch.store.contract;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 캠페인 담당이 상태 변경 또는 일일 요약 준비 시 전달하는 요약 값이다.
 *
 * @param businessDate 요약을 적용할 서울 기준 날짜
 * @param campaignStatus 해당 날짜의 캠페인 상태
 * @param discountTargetType 전체 할인인 ALL 또는 특정 메뉴 할인인 MENU
 * @param discountType 할인율인 PERCENT 또는 정액 할인인 AMOUNT
 * @param discountValue PERCENT이면 퍼센트 값, AMOUNT이면 원 단위 금액
 * @param targetMenuId MENU 할인의 대상 메뉴 ID이며 ALL이면 null
 * @param usableStartTime 쿠폰을 사용할 수 있는 시작 시각
 * @param usableEndTime 쿠폰을 사용할 수 있는 종료 시각이며 시작 시각보다 늦어야 함
 */
public record StoreCampaignSummaryCommand(
        LocalDate businessDate,
        StoreCampaignStatus campaignStatus,
        StoreDiscountTargetType discountTargetType,
        StoreDiscountType discountType,
        int discountValue,
        Long targetMenuId,
        LocalTime usableStartTime,
        LocalTime usableEndTime
) { }
