package com.lunchcatch.coupon.service;

import com.lunchcatch.coupon.entity.CouponEventStatus;
import com.lunchcatch.coupon.repository.CouponEventRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CouponEventStateService {

    private final CouponEventRepository couponEventRepository;
    private final Clock clock;

    public int changeCampaignStatus(long campaignId, LocalDate businessDate, boolean servable, LocalDateTime changedAt) {
        CouponEventStatus status = servable ? CouponEventStatus.ACTIVE : CouponEventStatus.PAUSED;
        return couponEventRepository.updateCampaignStatus(campaignId, businessDate, status, changedAt,
                LocalDateTime.now(clock));
    }

    public int endCampaign(long campaignId, LocalDateTime changedAt) {
        return couponEventRepository.endCampaign(campaignId, changedAt, LocalDateTime.now(clock));
    }
}
