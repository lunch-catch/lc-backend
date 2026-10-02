package com.launchcatch.admin.service;

import com.launchcatch.coupon.service.CouponIssueService;
import org.springframework.stereotype.Service;

@Service
public class AdminCouponService {

    private final CouponIssueService couponIssueService;

    public AdminCouponService(CouponIssueService couponIssueService) {
        this.couponIssueService = couponIssueService;
    }

    // 관리자가 수동으로 쿠폰을 발급한다
    public boolean issueManually(Long campaignId) {
        return couponIssueService.issue(campaignId);
    }
}
