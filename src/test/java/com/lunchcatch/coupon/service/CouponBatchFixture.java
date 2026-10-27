package com.lunchcatch.coupon.service;

import com.lunchcatch.campaign.contract.CampaignInfo;
import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.coupon.entity.CouponEvent;
import com.lunchcatch.coupon.entity.DiscountTargetType;
import com.lunchcatch.coupon.entity.DiscountType;
import com.lunchcatch.coupon.entity.MemberCoupon;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

final class CouponBatchFixture {
    static final LocalDate DATE = LocalDate.of(2026, 10, 20);

    private CouponBatchFixture() { }

    static CouponEvent event() {
        CouponEvent event = CouponEvent.prepare(91L, DATE, 3, DATE.atTime(11, 0), DATE.atTime(15, 0),
                new CouponEvent.Snapshot(10L, "식당", null, DiscountTargetType.ALL, DiscountType.AMOUNT,
                        1000, null, null, null, null));
        event.initializeCampaignState(true, DATE.atStartOfDay());
        ReflectionTestUtils.setField(event, "id", 1L);
        return event;
    }

    static MemberCoupon coupon() {
        MemberCoupon coupon = MemberCoupon.issue(17L, 1L, UUID.randomUUID(), DATE.atTime(11, 0), DATE.atTime(15, 0));
        ReflectionTestUtils.setField(coupon, "id", 2L);
        return coupon;
    }

    static CampaignInfo campaign(CampaignStatus status) {
        return campaign(status, com.lunchcatch.campaign.contract.DiscountTargetType.ALL);
    }

    static CampaignInfo menuCampaign() {
        return campaign(CampaignStatus.ACTIVE, com.lunchcatch.campaign.contract.DiscountTargetType.MENU);
    }

    private static CampaignInfo campaign(CampaignStatus status, com.lunchcatch.campaign.contract.DiscountTargetType target) {
        return new CampaignInfo(91L, 1L, 10L, status, 3, LocalTime.of(11, 0), LocalTime.of(15, 0),
                DATE.minusDays(1), DATE.plusDays(1), target,
                com.lunchcatch.campaign.contract.DiscountType.AMOUNT, 1000,
                target == com.lunchcatch.campaign.contract.DiscountTargetType.MENU ? 100L : null, 1000,
                com.lunchcatch.campaign.contract.TargetGender.ALL, List.of(), DATE.minusDays(1).atStartOfDay(),
                null, DATE.atStartOfDay(), null, 10000L);
    }
}
