package com.launchcatch.coupon.service;

import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.DiscountTargetType;
import com.launchcatch.coupon.entity.DiscountType;
import com.launchcatch.coupon.entity.MemberCoupon;
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
        return campaign(status, com.launchcatch.campaign.contract.DiscountTargetType.ALL);
    }

    static CampaignInfo menuCampaign() {
        return campaign(CampaignStatus.ACTIVE, com.launchcatch.campaign.contract.DiscountTargetType.MENU);
    }

    private static CampaignInfo campaign(CampaignStatus status, com.launchcatch.campaign.contract.DiscountTargetType target) {
        return new CampaignInfo(91L, 1L, 10L, status, 3, LocalTime.of(11, 0), LocalTime.of(15, 0),
                DATE.minusDays(1), DATE.plusDays(1), target,
                com.launchcatch.campaign.contract.DiscountType.AMOUNT, 1000,
                target == com.launchcatch.campaign.contract.DiscountTargetType.MENU ? 100L : null, 1000,
                com.launchcatch.campaign.contract.TargetGender.ALL, List.of(), DATE.minusDays(1).atStartOfDay(),
                null, DATE.atStartOfDay(), null, 10000L);
    }
}
