package com.launchcatch.coupon.repository;

import static com.launchcatch.coupon.entity.QCouponUsageHistory.couponUsageHistory;

import com.launchcatch.coupon.entity.CouponUsageHistory;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class CouponUsageHistoryRepositoryImpl implements CouponUsageHistoryRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<CouponUsageHistory> findPage(Long storeId, LocalDateTime fromInclusive, LocalDateTime toExclusive,
            LocalDateTime cursorUseTime, Long cursorId, int limit) {
        BooleanBuilder conditions = new BooleanBuilder(couponUsageHistory.storeId.eq(storeId))
                .and(couponUsageHistory.createdAt.goe(fromInclusive))
                .and(couponUsageHistory.createdAt.lt(toExclusive));

        // 사용 시각이 같으면 이력 ID로 순서를 구분하여 직전 페이지 다음부터 조회한다.
        if (cursorUseTime != null) {
            conditions.and(couponUsageHistory.createdAt.lt(cursorUseTime)
                    .or(couponUsageHistory.createdAt.eq(cursorUseTime).and(couponUsageHistory.id.lt(cursorId))));
        }

        return queryFactory.selectFrom(couponUsageHistory)
                .where(conditions)
                .orderBy(couponUsageHistory.createdAt.desc(), couponUsageHistory.id.desc())
                .limit(limit)
                .fetch();
    }
}
