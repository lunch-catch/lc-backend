package com.launchcatch.coupon.contract;

import java.time.LocalDateTime;

/*
 * 쿠폰 발급을 알린다. 받는 쪽은 분석이고 전환 퍼널에 기록한다 (설계 문서 2.4 절).
 * 발행 도메인의 contract 에 두어야 수신자가 쿠폰 내부를 import 하지 않는다.
 */
public record CouponIssuedEvent(Long campaignId, Long memberId, LocalDateTime issuedAt) {
}
