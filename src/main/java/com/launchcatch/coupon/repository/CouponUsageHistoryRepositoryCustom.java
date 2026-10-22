package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.entity.CouponUsageHistory;
import java.time.LocalDateTime;
import java.util.List;

public interface CouponUsageHistoryRepositoryCustom {

    // 매장과 사용 시각 범위 안에서 커서 다음의 사용 이력을 내림차순으로 조회한다.
    List<CouponUsageHistory> findPage(Long storeId, LocalDateTime fromInclusive, LocalDateTime toExclusive,
            LocalDateTime cursorUseTime, Long cursorId, int limit);
}
