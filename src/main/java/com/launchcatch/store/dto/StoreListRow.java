package com.launchcatch.store.dto;

import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import java.math.BigDecimal;

/*
 * 가게와 캠페인, 메뉴에서 선택한 값과 계산 거리를 담는 목록 조회 결과다.
 * distanceMeters는 반올림 전 거리이며 커서는 이 값과 storeId만 사용한다.
 * 나머지 필드는 목록 응답을 만드는 데 사용한다.
 */
public record StoreListRow(
        long storeId, String name, String businessCategory, BigDecimal longitude, BigDecimal latitude,
        double distanceMeters, Long campaignId, StoreDiscountTargetType discountTargetType,
        StoreDiscountType discountType, Integer discountValue, Long targetMenuId, String targetMenuName,
        Long targetMenuPrice
) { }
