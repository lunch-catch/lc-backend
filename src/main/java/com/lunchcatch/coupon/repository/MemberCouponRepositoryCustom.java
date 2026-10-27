package com.lunchcatch.coupon.repository;

import com.lunchcatch.coupon.CouponListFilter;
import com.lunchcatch.coupon.entity.MemberCoupon;
import java.time.LocalDateTime;
import java.util.List;

public interface MemberCouponRepositoryCustom {

    List<MemberCoupon> findPage(Long memberId, CouponListFilter filter, LocalDateTime now,
            LocalDateTime cursorIssueTime, Long cursorId, int limit);
}
