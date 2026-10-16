package com.launchcatch.coupon.contract;

import java.time.LocalDate;
import java.util.List;

/** 쿠폰이 소유한 집계를 분석에 공개하며 날짜와 상태의 기준은 쿠폰 집계 조회 계약을 따른다. */
public interface CouponStatsQueryService {

    List<DailyCampaignCouponStats> dailyCampaignStats(LocalDate businessDate);

    MemberCouponStats memberStats(long memberId);
}
