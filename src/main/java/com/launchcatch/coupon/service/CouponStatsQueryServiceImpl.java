package com.launchcatch.coupon.service;

import com.launchcatch.coupon.contract.CouponStatsQueryService;
import com.launchcatch.coupon.contract.DailyCampaignCouponStats;
import com.launchcatch.coupon.contract.MemberCouponStats;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class CouponStatsQueryServiceImpl implements CouponStatsQueryService {

    private final CouponEventRepository events;
    private final MemberCouponRepository coupons;

    @Override
    public List<DailyCampaignCouponStats> dailyCampaignStats(LocalDate businessDate) {
        Objects.requireNonNull(businessDate, "영업일은 필수다.");
        return events.findDailyCampaignStats(businessDate, businessDate.atStartOfDay(),
                        businessDate.plusDays(1).atStartOfDay()).stream()
                .map(row -> new DailyCampaignCouponStats(row.getCampaignId(), row.getOpenCount(),
                        row.getIssuedCount(), row.getUsedCount(), row.getExpiredCount()))
                .toList();
    }

    @Override
    public MemberCouponStats memberStats(long memberId) {
        if (memberId <= 0) {
            throw new IllegalArgumentException("회원 ID는 양수여야 한다.");
        }
        var summary = coupons.findSummaryByMemberId(memberId);
        return new MemberCouponStats(summary.usedCouponCount(), summary.totalDiscountAmount());
    }
}
