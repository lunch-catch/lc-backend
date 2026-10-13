package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.entity.CouponEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {
}
