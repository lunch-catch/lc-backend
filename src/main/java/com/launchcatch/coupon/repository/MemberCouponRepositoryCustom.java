package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.CouponListFilter;
import com.launchcatch.coupon.entity.MemberCoupon;
import java.time.LocalDateTime;
import java.util.List;

public interface MemberCouponRepositoryCustom {

    List<MemberCoupon> findPage(Long memberId, CouponListFilter filter, LocalDateTime now,
            LocalDateTime cursorIssueTime, Long cursorId, int limit);
}
